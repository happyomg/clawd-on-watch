"use strict";

const childProcess = require("child_process");
const { EventEmitter } = require("events");
const path = require("path");

function splitLines(buffer) {
  const lines = buffer.split(/\r?\n/);
  return { lines: lines.slice(0, -1), rest: lines[lines.length - 1] || "" };
}

class WatchSidecarClient {
  constructor(options = {}) {
    this.command = options.command || "python3";
    this.args = options.args || [];
    this.spawnOptions = options.spawnOptions || {};
    this.log = options.log || (() => {});
    this.onStatus = options.onStatus || (() => {});
    this.onDevices = options.onDevices || (() => {});
    this.onError = options.onError || (() => {});
    this.onTransportStateChanged = options.onTransportStateChanged || (() => {});
    this.onApprovalResponse = options.onApprovalResponse || (() => {});
    this.onThemeSynced = options.onThemeSynced || (() => {});

    this.proc = null;
    this.started = false;
    this._stopping = false;
    this._stdoutBuf = "";
    this._stderrBuf = "";

    this.transport = {
      connected: false,
      secure: false,
      send: (payload) => {
        if (payload && payload.type === "approval_request") {
          this._writeStdin(payload);
        } else {
          this._writeStdin({ type: "snapshot", payload });
        }
      },
    };
  }

  start() {
    if (this.started) return;
    if (this.proc) {
      try { this.proc.kill("SIGKILL"); } catch (_) {}
      this.proc = null;
    }
    const env = { ...process.env, PYTHONIOENCODING: "utf-8:replace", ...(this.spawnOptions.env || {}) };
    this.proc = childProcess.spawn(this.command, this.args, {
      ...this.spawnOptions,
      env,
      stdio: ["pipe", "pipe", "pipe"],
    });
    // Swallow async pipe errors (EPIPE etc.) on all three stdio streams so a
    // dying sidecar can never crash the main process. The synchronous
    // try/catch in _writeStdin only catches sync throws, not the EPIPE that
    // surfaces from WriteWrap.onWriteComplete after the pipe is half-closed.
    if (this.proc.stdin) this.proc.stdin.on("error", () => {});
    if (this.proc.stdout) this.proc.stdout.on("error", () => {});
    if (this.proc.stderr) this.proc.stderr.on("error", () => {});
    this.started = true;
    this.proc.stdout.setEncoding("utf-8");
    this.proc.stderr.setEncoding("utf-8");

    this.proc.stdout.on("data", (chunk) => {
      this._stdoutBuf += chunk;
      const { lines, rest } = splitLines(this._stdoutBuf);
      this._stdoutBuf = rest;
      for (const line of lines) this._handleLine(line);
    });

    this.proc.stderr.on("data", (chunk) => {
      this._stderrBuf += chunk;
      const { lines, rest } = splitLines(this._stderrBuf);
      this._stderrBuf = rest;
      for (const line of lines) this.log("warn", `bridge stderr: ${line}`);
    });

    this.proc.on("exit", (code, signal) => {
      if (this._stopping) { this._stopping = false; this.proc = null; return; }
      this.started = false;
      const wasConnected = this.transport.connected;
      this.transport.connected = false;
      this.transport.secure = false;
      this.log("info", `sidecar exited code=${code} signal=${signal}`);
      this.onTransportStateChanged({ connected: false, previous: { connected: wasConnected } });
    });

    this.proc.on("error", (err) => {
      if (this._stopping) return;
      this.started = false;
      this.onError(err);
    });
  }

  stop() {
    if (!this.proc) return;
    this._stopping = true;
    if (this._disconnectTimer) { clearTimeout(this._disconnectTimer); this._disconnectTimer = null; }
    // Best-effort graceful stop: ask Python to break out of its loop, then
    // half-close stdin so it sees EOF. Both are wrapped because the pipe may
    // already be torn down.
    this._writeStdin({ type: "stop" });
    const proc = this.proc;
    try {
      if (proc.stdin && !proc.stdin.destroyed && !proc.stdin.writableEnded) {
        proc.stdin.end();
      }
    } catch (_) {}
    try { proc.kill("SIGTERM"); } catch (_) {}
    this.started = false;
    this.transport.connected = false;
    this.transport.secure = false;
    const graceTimer = setTimeout(() => {
      if (this.proc === proc) {
        try { proc.kill("SIGKILL"); } catch (_) {}
        this.proc = null;
      }
      this._stopping = false;
    }, 3000);
    if (graceTimer && typeof graceTimer.unref === "function") graceTimer.unref();
  }

  connect(target) {
    if (this._disconnectTimer) { clearTimeout(this._disconnectTimer); this._disconnectTimer = null; }
    const address = typeof target === "string" ? target : (target && target.address) || "";
    if (address) this._writeStdin({ type: "connect", address });
  }

  /**
   * Drop the active BLE link without killing the Python sidecar. The bridge
   * stops auto-reconnect until a new connect command arrives. This keeps the
   * sidecar process alive across UI disconnect/reconnect cycles, which avoids
   * the EPIPE / cold-start races of toggling `enabled` to bounce the process.
   */
  disconnect() {
    this._writeStdin({ type: "disconnect" });
    if (this._disconnectTimer) clearTimeout(this._disconnectTimer);
    this._disconnectTimer = setTimeout(() => {
      this._disconnectTimer = null;
      if (this.transport.connected) {
        this.onError({ code: "DISCONNECT_STUCK", message: "disconnect timed out" });
      }
    }, 1500);
  }

  /**
   * Stream a theme to the watch via CWD5. `frames` is the ordered list from
   * buildThemeFrames (manifest → chunks → done); the bridge writes each frame
   * to the Theme Data characteristic in order.
   */
  sendThemeData(frames) {
    if (!Array.isArray(frames) || frames.length === 0) return;
    this._writeStdin({ type: "theme_sync", frames });
  }

  scan() {
    this._writeStdin({ type: "scan" });
  }

  /**
   * Direct reconnect to a known address without scanning. Falls back to
   * last_address (from the most recent successful connection) or the CLI
   * --address argument inside the bridge.
   */
  reconnect(address) {
    this._writeStdin({ type: "reconnect", address: address || "" });
  }

  _writeStdin(obj) {
    if (!this.proc) return;
    const stdin = this.proc.stdin;
    if (!stdin || stdin.destroyed || stdin.writableEnded || stdin.writable === false) return;
    try {
      // Pass an error callback so async EPIPE doesn't go to the stream's
      // "error" event as an unhandled emit on older Node versions.
      stdin.write(JSON.stringify(obj) + "\n", () => {});
    } catch (_) {}
  }

  _handleLine(line) {
    const text = line.trim();
    if (!text) return;
    let msg;
    try { msg = JSON.parse(text); } catch (_) { return; }
    const type = msg.type || "";

    if (type === "status") {
      // Scanning-only status update (no connection state change)
      if ("scanning" in msg && !("connected" in msg)) {
        this.onStatus(msg);
        return;
      }
      // Connection status
      if (!msg.connected && this._disconnectTimer) {
        clearTimeout(this._disconnectTimer);
        this._disconnectTimer = null;
      }
      const wasConnected = this.transport.connected;
      this.transport.connected = !!msg.connected;
      this.transport.secure = !!msg.connected;
      if (wasConnected !== this.transport.connected) {
        this.onTransportStateChanged({
          connected: this.transport.connected,
          secure: this.transport.secure,
          previous: { connected: wasConnected },
        });
      }
      this.onStatus(msg);
    } else if (type === "devices") {
      this.onDevices(msg.items || []);
    } else if (type === "approval_response") {
      this.onApprovalResponse(msg);
    } else if (type === "theme_synced") {
      this.onThemeSynced(msg);
    } else if (type === "error") {
      this.onError({ code: msg.code || "SIDECAR_ERROR", message: msg.message || "" });
    }
  }
}

module.exports = { WatchSidecarClient };
