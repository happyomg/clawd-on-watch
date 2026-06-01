#!/usr/bin/env python3
"""
Watch Buddy Bridge — BLE Central sidecar for the Clawd watch backend.

Connects to a Wear OS watch running the Clawd app (BLE Peripheral) and
forwards session snapshots from stdin to GATT characteristic CWD1, approval
requests to CWD2, and relays approval responses from CWD3 back to stdout.

stdio protocol (newline-delimited JSON, same as clawstick sidecar):

  stdin  <- {"type":"snapshot","payload":{...}}
  stdin  <- {"type":"approval_request","requestId":"...","tool":"Bash",...}
  stdin  <- {"type":"connect","address":"AA:BB:CC:DD:EE:FF"}
  stdin  <- {"type":"scan"}
  stdin  <- {"type":"stop"}

  stdout -> {"type":"status","connected":true,"deviceName":"Android Watch"}
  stdout -> {"type":"devices","items":[{"address":"...","name":"...","rssi":-54}]}
  stdout -> {"type":"approval_response","requestId":"...","decision":"allow"}
  stdout -> {"type":"error","code":"...","message":"..."}
"""

import asyncio
import json
import sys
import argparse

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    sys.stdout.write(json.dumps({
        "type": "error",
        "code": "MISSING_BLEAK",
        "message": "Python 'bleak' package is not installed. Run: pip install bleak",
    }) + "\n")
    sys.stdout.flush()
    sys.exit(1)

CWD_SERVICE = "00000cd0-0000-1000-8000-00805f9b34fb"
CWD1_STATE = "00000cd1-0000-1000-8000-00805f9b34fb"
CWD2_APPROVAL_REQ = "00000cd2-0000-1000-8000-00805f9b34fb"
CWD3_APPROVAL_RESP = "00000cd3-0000-1000-8000-00805f9b34fb"
CWD4_META = "00000cd4-0000-1000-8000-00805f9b34fb"
CWD5_THEME = "00000cd5-0000-1000-8000-00805f9b34fb"

RECONNECT_DELAYS = [0, 2, 5, 10, 15, 30, 30]


def emit(obj):
    line = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


def emit_status(connected, device_name=None, theme_hash=None):
    msg = {"type": "status", "connected": connected}
    if device_name:
        msg["deviceName"] = device_name
    if theme_hash is not None:
        msg["themeHash"] = theme_hash
    emit(msg)


def emit_error(code, message):
    emit({"type": "error", "code": code, "message": message})


def _filter_devices(devices_advs, name_prefix):
    results = []
    for addr, (device, adv) in devices_advs.items():
        uuids = adv.service_uuids or []
        name = device.name or ""
        sys.stderr.write(
            f"[scan] seen addr={addr} name={name!r} uuids={uuids} rssi={adv.rssi}\n"
        )
        if CWD_SERVICE in uuids or (name_prefix and name.startswith(name_prefix)):
            results.append({
                "address": device.address,
                "name": name,
                "rssi": adv.rssi,
            })
    return results


async def scan_for_watch(name_prefix, timeout=10.0):
    # Targeted scan with service UUID filter (uses macOS cache, fast)
    devices_advs = await BleakScanner.discover(
        timeout=timeout, return_adv=True, service_uuids=[CWD_SERVICE]
    )
    results = _filter_devices(devices_advs, name_prefix)
    if results:
        return results
    # Fallback: general scan — macOS may not report service UUID in adv data
    # for peripherals that were recently connected (cache stale state).
    sys.stderr.write("[scan] targeted scan empty, trying general scan\n")
    devices_advs = await BleakScanner.discover(
        timeout=timeout, return_adv=True
    )
    return _filter_devices(devices_advs, name_prefix)


async def read_stdin_lines(queue, on_eof=None):
    loop = asyncio.get_event_loop()
    reader = asyncio.StreamReader()
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
    while True:
        line = await reader.readline()
        if not line:
            if on_eof:
                on_eof()
            await queue.put(None)
            break
        text = line.decode("utf-8", errors="replace").strip()
        if text:
            try:
                await queue.put(json.loads(text))
            except json.JSONDecodeError:
                pass


async def run(args):
    client = None
    connected_name = None
    last_address = None
    last_snapshot_data = None
    reconnect_attempt = 0
    reconnect_task = None
    stopping = False
    manual_disconnect = False
    current_op = None
    disconnect_event = asyncio.Event()
    stdin_queue = asyncio.Queue()

    def on_stdin_eof():
        nonlocal stopping
        stopping = True

    stdin_task = asyncio.ensure_future(read_stdin_lines(stdin_queue, on_eof=on_stdin_eof))
    stdin_task.add_done_callback(lambda t: t.exception() if not t.cancelled() and t.exception() else None)

    import signal
    loop = asyncio.get_event_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, lambda: stdin_queue.put_nowait(None))

    # ── Cancellable GATT wrapper ──────────────────────────────────────────
    async def do_gatt(coro):
        nonlocal current_op
        current_op = asyncio.ensure_future(coro)
        try:
            return await current_op
        finally:
            current_op = None

    # ── Disconnect watcher (priority interrupt) ───────────────────────────
    async def disconnect_watcher():
        nonlocal client, connected_name, manual_disconnect, reconnect_task, reconnect_attempt
        while not stopping:
            await disconnect_event.wait()
            disconnect_event.clear()
            manual_disconnect = True
            if reconnect_task:
                reconnect_task.cancel()
                reconnect_task = None
            reconnect_attempt = 0
            if current_op and not current_op.done():
                current_op.cancel()
            c = client
            client = None
            if c:
                try:
                    goodbye = json.dumps({"type": "disconnect"}, separators=(",", ":")).encode("utf-8")
                    await asyncio.wait_for(c.write_gatt_char(CWD1_STATE, goodbye, response=False), timeout=0.2)
                except Exception:
                    pass
                try:
                    await asyncio.wait_for(c.disconnect(), timeout=1.0)
                except Exception:
                    pass
                still_connected = False
                try:
                    still_connected = c.is_connected
                except Exception:
                    pass
                if still_connected:
                    emit_error("DISCONNECT_STUCK", "BLE link did not close; sidecar restart recommended")
            connected_name = None
            emit_status(False)

    watcher_task = asyncio.ensure_future(disconnect_watcher())
    watcher_task.add_done_callback(lambda t: t.exception() if not t.cancelled() and t.exception() else None)

    # ── Reconnect logic ───────────────────────────────────────────────────
    async def schedule_reconnect():
        nonlocal reconnect_task
        if stopping or reconnect_task is not None:
            return
        addr = last_address
        if not addr:
            reconnect_task = asyncio.ensure_future(reconnect_via_scan())
        elif reconnect_attempt >= 3:
            reconnect_task = asyncio.ensure_future(reconnect_via_scan())
        else:
            reconnect_task = asyncio.ensure_future(reconnect_to(addr))

    async def reconnect_to(address):
        nonlocal reconnect_task, reconnect_attempt
        delay = RECONNECT_DELAYS[min(reconnect_attempt, len(RECONNECT_DELAYS) - 1)]
        reconnect_attempt += 1
        await asyncio.sleep(delay)
        try:
            if stopping or (client and client.is_connected):
                return
            await connect_to(address)
            if not client or not client.is_connected:
                await schedule_reconnect()
        finally:
            reconnect_task = None

    async def reconnect_via_scan():
        nonlocal reconnect_task, reconnect_attempt
        delay = RECONNECT_DELAYS[min(reconnect_attempt, len(RECONNECT_DELAYS) - 1)]
        reconnect_attempt += 1
        await asyncio.sleep(delay)
        try:
            if stopping or (client and client.is_connected):
                return
            items = await scan_for_watch(args.name_prefix, timeout=args.scan_timeout)
            emit({"type": "devices", "items": items})
            target = None
            for item in items:
                if last_address and item["address"].lower() == last_address.lower():
                    target = item["address"]
                    break
            if not target and items:
                target = items[0]["address"]
            if target:
                await connect_to(target)
                if not client or not client.is_connected:
                    await schedule_reconnect()
            else:
                await schedule_reconnect()
        except Exception as e:
            import sys
            sys.stderr.write(f"[reconnect_via_scan] error: {e}\n")
            sys.stderr.flush()
            await schedule_reconnect()
        finally:
            reconnect_task = None

    async def force_disconnect():
        nonlocal client
        if client:
            try:
                await asyncio.wait_for(client.disconnect(), timeout=2.0)
            except Exception:
                pass
            client = None
            emit_status(False)
            if not stopping and not manual_disconnect:
                await schedule_reconnect()

    def on_disconnect(_client):
        nonlocal client
        if _client is not client and client is not None:
            return
        client = None
        emit_status(False)
        if not stopping and not manual_disconnect:
            asyncio.ensure_future(schedule_reconnect())

    def on_cwd3_notify(_sender, data):
        try:
            resp = json.loads(data.decode("utf-8"))
            emit({
                "type": "approval_response",
                "requestId": resp.get("requestId", ""),
                "decision": resp.get("decision", "deny"),
            })
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            emit_error("CWD3_PARSE_ERROR", f"Bad approval response: {e}")

    # ── Connect ───────────────────────────────────────────────────────────
    async def connect_to(address):
        nonlocal client, connected_name, last_address, reconnect_attempt, reconnect_task
        if reconnect_task:
            reconnect_task.cancel()
            reconnect_task = None
        if client and client.is_connected:
            old_client = client
            client = None
            try:
                await asyncio.wait_for(old_client.disconnect(), timeout=3.0)
            except Exception:
                pass

        try:
            c = BleakClient(address, disconnected_callback=on_disconnect, services=[CWD_SERVICE])
            await c.connect(timeout=args.connect_timeout)
            if not c.is_connected:
                emit_error("CONNECT_FAILED", f"failed to connect to {address}")
                return

            try:
                meta_bytes = await asyncio.wait_for(c.read_gatt_char(CWD4_META), timeout=5.0)
                meta = json.loads(meta_bytes.decode("utf-8"))
            except (asyncio.TimeoutError, Exception) as e:
                # CWD4 read failed — likely macOS GATT cache stale. Proceed
                # without meta (desktop will see empty themeHash → push theme).
                import sys
                sys.stderr.write(f"[connect] CWD4 read failed: {e}, proceeding without meta\n")
                sys.stderr.flush()
                meta = {}
            connected_name = meta.get("deviceName", address)
            theme_hash = meta.get("themeHash")

            await c.start_notify(CWD3_APPROVAL_RESP, on_cwd3_notify)

            client = c
            last_address = address
            reconnect_attempt = 0
            emit_status(True, connected_name, theme_hash)
            if last_snapshot_data is not None:
                try:
                    await asyncio.wait_for(
                        c.write_gatt_char(CWD1_STATE, last_snapshot_data, response=True),
                        timeout=3.0,
                    )
                except Exception:
                    pass
        except Exception as e:
            emit_error("CONNECT_FAILED", str(e))

    # ── Scan (no auto-connect, emits scanning status) ─────────────────────
    async def do_scan():
        emit({"type": "status", "scanning": True})
        try:
            items = await scan_for_watch(args.name_prefix, timeout=args.scan_timeout)
            emit({"type": "devices", "items": items})
        except Exception as e:
            emit_error("SCAN_FAILED", str(e))
        finally:
            emit({"type": "status", "scanning": False})

    # ── Initial action ────────────────────────────────────────────────────
    if args.address:
        await connect_to(args.address)
    else:
        await do_scan()

    if not client or not client.is_connected:
        if not manual_disconnect:
            await schedule_reconnect()

    # ── Main loop ─────────────────────────────────────────────────────────
    while True:
        try:
            msg = await asyncio.wait_for(stdin_queue.get(), timeout=15.0)
        except asyncio.TimeoutError:
            if client and client.is_connected:
                try:
                    probe = last_snapshot_data if last_snapshot_data is not None else b"{}"
                    await do_gatt(asyncio.wait_for(
                        client.write_gatt_char(CWD1_STATE, probe, response=True),
                        timeout=5.0,
                    ))
                except asyncio.CancelledError:
                    pass
                except Exception:
                    emit_error("HEALTH_CHECK_FAILED", "liveness probe failed, reconnecting")
                    await force_disconnect()
            elif not client or not client.is_connected:
                if not reconnect_task and not stopping and not manual_disconnect:
                    await schedule_reconnect()
                elif reconnect_task:
                    pass  # reconnect already scheduled
                elif manual_disconnect:
                    emit({"type": "error", "code": "MANUAL_DISCONNECT", "message": "manual_disconnect=true, not reconnecting"})
            continue
        if msg is None:
            break

        msg_type = msg.get("type", "")

        if msg_type == "snapshot":
            payload = msg.get("payload", msg)
            data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
            last_snapshot_data = data.encode("utf-8")
            if client and client.is_connected:
                try:
                    await do_gatt(client.write_gatt_char(CWD1_STATE, last_snapshot_data))
                except asyncio.CancelledError:
                    pass
                except Exception as e:
                    emit_error("WRITE_FAILED", str(e))
                    await force_disconnect()

        elif msg_type == "approval_request":
            if client and client.is_connected:
                req = {k: v for k, v in msg.items() if k != "type"}
                data = json.dumps(req, ensure_ascii=False, separators=(",", ":"))
                try:
                    await do_gatt(client.write_gatt_char(CWD2_APPROVAL_REQ, data.encode("utf-8")))
                except asyncio.CancelledError:
                    pass
                except Exception as e:
                    emit_error("WRITE_FAILED", str(e))
                    await force_disconnect()

        elif msg_type == "theme_frame":
            # Write each theme frame immediately via CWD1 (with response for
            # reliability). Each frame is small (~500B), and response=True
            # provides back-pressure without flooding.
            if client and client.is_connected:
                fr = {k: v for k, v in msg.items() if k != "type"}
                payload = json.dumps(fr, ensure_ascii=False, separators=(",", ":"))
                try:
                    payload_bytes = payload.encode("utf-8")
                    # Use Write Without Response for large frames (avoids macOS
                    # Prepared Write issues), Write With Response for small ones.
                    use_response = len(payload_bytes) <= 512
                    await client.write_gatt_char(CWD1_STATE, payload_bytes, response=use_response)
                    await asyncio.sleep(0.05)  # 50ms pacing per frame
                    if not hasattr(run, '_theme_count'):
                        run._theme_count = 0
                        run._theme_hash = ''
                    run._theme_count += 1
                    if fr.get("t") == "done":
                        run._theme_hash = fr.get("hash", "")
                except Exception as e:
                    emit_error("THEME_WRITE_FAILED", str(e))
                    await force_disconnect()

        elif msg_type == "theme_done":
            count = getattr(run, '_theme_count', 0)
            theme_hash = getattr(run, '_theme_hash', '')
            run._theme_count = 0
            run._theme_hash = ''
            emit({"type": "theme_synced", "hash": theme_hash, "frames": count})

        elif msg_type == "connect":
            addr = msg.get("address", "")
            if addr:
                if reconnect_task:
                    reconnect_task.cancel()
                    reconnect_task = None
                reconnect_attempt = 0
                manual_disconnect = False
                await connect_to(addr)
                if not client or not client.is_connected:
                    if not stopping:
                        await schedule_reconnect()

        elif msg_type == "scan":
            if reconnect_task:
                reconnect_task.cancel()
                reconnect_task = None
            reconnect_attempt = 0
            manual_disconnect = False
            await do_scan()

        elif msg_type == "reconnect":
            addr = msg.get("address") or last_address or args.address
            if addr:
                if reconnect_task:
                    reconnect_task.cancel()
                    reconnect_task = None
                reconnect_attempt = 0
                manual_disconnect = False
                await connect_to(addr)
                if not client or not client.is_connected:
                    if not stopping:
                        await schedule_reconnect()
            else:
                emit_error("NO_ADDRESS", "No saved address to reconnect to")

        elif msg_type == "disconnect":
            disconnect_event.set()

        elif msg_type == "stop":
            break

    # ── Shutdown ──────────────────────────────────────────────────────────
    stopping = True
    disconnect_event.set()
    watcher_task.cancel()

    stopping = True
    if reconnect_task:
        reconnect_task.cancel()
    if client and client.is_connected:
        await client.disconnect()
    emit_status(False)


def main():
    parser = argparse.ArgumentParser(description="Watch Buddy Bridge")
    parser.add_argument("--backend", default="watch")
    parser.add_argument("--name-prefix", default="Clawd")
    parser.add_argument("--address", default="")
    parser.add_argument("--scan-timeout", type=float, default=10.0)
    parser.add_argument("--connect-timeout", type=float, default=15.0)
    args = parser.parse_args()

    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
