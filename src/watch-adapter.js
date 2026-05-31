"use strict";

const fs = require("fs");
const path = require("path");
const { WatchSidecarClient } = require("./watch-sidecar-client");
const { WatchController } = require("./watch-controller");
const { normalizeWatchSettings } = require("./watch-settings");
const { buildThemeFrames } = require("./watch-theme-transfer");

function truthy(value) {
  if (value === true) return true;
  if (typeof value !== "string") return false;
  return /^(1|true|yes|on)$/i.test(value.trim());
}

function resolveWatchBridgeScript(env = process.env) {
  if (env.CLAWD_WATCH_BUDDY_SIDECAR) return env.CLAWD_WATCH_BUDDY_SIDECAR;
  const packaged = typeof process !== "undefined" && process.resourcesPath
    ? path.join(process.resourcesPath, "sidecars", "watch-bridge", "watch_buddy_bridge.py")
    : null;
  if (packaged && fs.existsSync(packaged)) return packaged;
  return path.join(__dirname, "..", "scripts", "watch_buddy_bridge.py");
}

function classifyWatchIssue(err) {
  const code = String((err && err.code) || "").trim();
  const message = String((err && err.message) || err || "").trim();
  const lower = message.toLowerCase();
  if (code === "MISSING_BLEAK") {
    return { code, category: "missing_bleak", retryable: false, message, hint: "Python bleak package is required. Use the Install button." };
  }
  if (code === "DISCONNECTED") {
    return { code, category: "disconnected", retryable: true, message, hint: "Check Bluetooth and keep the watch powered on." };
  }
  if (code === "ENOENT" || (lower.includes("spawn") && lower.includes("enoent"))) {
    return { code: "PYTHON_MISSING", category: "python_missing", retryable: false, message, hint: "Python3 is not installed." };
  }
  if (code === "SIDECAR_EXIT") {
    return { code, category: "sidecar_exited", retryable: true, message, hint: "The watch bridge stopped unexpectedly." };
  }
  return { code: code || "WATCH_ERROR", category: "watch_error", retryable: true, message, hint: "Watch bridge reported an error." };
}

function watchApprovalId(perm) {
  const sid = (perm.sessionId || "").slice(-8);
  const tool = perm.toolName || perm.tool || "";
  const ts = perm.createdAt || 0;
  return `${sid}:${tool}:${ts}`;
}

function createWatchAdapter(options = {}) {
  const env = options.env || process.env;
  const log = typeof options.log === "function" ? options.log : () => {};
  const now = typeof options.now === "function" ? options.now : () => Date.now();
  const onStatusChanged = typeof options.onStatusChanged === "function" ? options.onStatusChanged : () => {};
  const setTimer = typeof options.setTimeout === "function" ? options.setTimeout : setTimeout;
  const clearTimer = typeof options.clearTimeout === "function" ? options.clearTimeout : clearTimeout;

  let sidecar = null;
  let controller = null;
  let started = false;
  let lastError = null;
  let lastDevices = [];
  let retryAttempt = 0;
  let restartTimer = null;
  let activeConfig = readConfig();
  // Tracks the time window between user-initiated connect and the first
  // "connected" status from the bridge. Surfaced to the UI so clicking a
  // device gives immediate feedback instead of looking dead until BLE settles.
  let isConnecting = false;
  let isScanning = false;

  // Theme sync state (Layer 2/3): the watch's last-reported fingerprint, whether
  // a transfer is in flight, and the hash we last pushed (so we don't re-push
  // the same theme repeatedly while the watch records it).
  let watchThemeHash = null;
  let themeSyncing = false;
  let lastSyncedHash = null;

  function readConfig() {
    const settings = typeof options.getSettings === "function" ? options.getSettings() : null;
    const config = normalizeWatchSettings(settings || {});
    if (settings === null || settings === undefined) {
      if (truthy(env.CLAWD_WATCH_ENABLED)) config.enabled = true;
    }
    if (truthy(env.CLAWD_WATCH_DISABLED)) config.enabled = false;
    if (env.CLAWD_WATCH_ADDRESS) {
      const addr = String(env.CLAWD_WATCH_ADDRESS).trim().slice(0, 120);
      if (addr && !/[\x00-\x1f\x7f]/.test(addr)) config.address = addr;
    }
    if (env.CLAWD_WATCH_NAME_PREFIX) {
      const prefix = String(env.CLAWD_WATCH_NAME_PREFIX).trim().slice(0, 40);
      if (prefix && !/[\x00-\x1f\x7f]/.test(prefix)) config.namePrefix = prefix;
    }
    return config;
  }

  function publishStatus(extra = {}) {
    const connected = !!(sidecar && sidecar.transport && sidecar.transport.connected);
    if (connected) isConnecting = false;
    const snapshot = {
      enabled: activeConfig.enabled,
      started,
      connected,
      // Surface isConnecting even when connected=true (e.g. switching devices
      // while still attached to the previous one). The panel layer decides
      // how to combine with its local pending intent.
      connecting: isConnecting,
      scanning: isScanning,
      address: activeConfig.address,
      namePrefix: activeConfig.namePrefix,
      permissionsEnabled: activeConfig.permissionsEnabled,
      lastError,
      retryAttempt,
      devices: lastDevices,
      ...extra,
    };
    onStatusChanged(snapshot);
    return snapshot;
  }

  function handleIssue(err, { restart = false } = {}) {
    const issue = classifyWatchIssue(err);
    retryAttempt += 1;
    const isSetupError = lastError && (lastError.category === "missing_bleak" || lastError.category === "python_missing");
    const isFollowup = issue.category === "sidecar_exited" || issue.category === "disconnected";
    const keepPrevious = isSetupError && isFollowup;
    if (!keepPrevious) {
      lastError = { code: issue.code, category: issue.category, message: issue.message, hint: issue.hint, retryable: issue.retryable, at: now() };
    }
    log(`watch ${issue.category}: ${issue.message}`);
    publishStatus();
    if (!issue.retryable) return;
    if (restart) scheduleRestart(Math.min(15000 * Math.pow(2, Math.min(retryAttempt - 1, 4)), 120000));
  }

  function scheduleRestart(delayMs) {
    if (restartTimer || !activeConfig.enabled) return;
    restartTimer = setTimer(() => {
      restartTimer = null;
      if (!activeConfig.enabled) return;
      try { cleanup({ keepConfig: true }); start(); } catch (err) { log(`restart failed: ${err.message || err}`); }
    }, delayMs);
  }

  function isConnected() {
    return !!(sidecar && sidecar.transport && sidecar.transport.connected);
  }

  // Compare the desktop's active-theme fingerprint against the watch's reported
  // hash; on mismatch, stream the theme (manifest + raw SVGs) over CWD5. Guards
  // against re-pushing a theme already in flight or already sent.
  function maybeSyncTheme() {
    if (!isConnected() || themeSyncing) return;
    const getFp = typeof options.getThemeFingerprint === "function" ? options.getThemeFingerprint : null;
    const getBundle = typeof options.getThemeBundle === "function" ? options.getThemeBundle : null;
    if (!getFp || !getBundle) return;
    const desktopHash = getFp();
    if (!desktopHash) return;
    if (watchThemeHash && desktopHash === watchThemeHash) return; // already in sync
    if (desktopHash === lastSyncedHash) return; // pushed; awaiting watch to finish
    let bundle;
    try { bundle = getBundle(); } catch (err) { log(`theme bundle failed: ${err.message || err}`); return; }
    if (!bundle || !bundle.fileData || !bundle.hash) return;
    let frames;
    try { frames = buildThemeFrames(bundle); } catch (err) { log(`theme framing failed: ${err.message || err}`); return; }
    if (!frames.length) return;
    themeSyncing = true;
    lastSyncedHash = desktopHash;
    log(`theme sync → watch: ${bundle.name} (${desktopHash}), ${frames.length} frames`);
    try {
      sidecar.sendThemeData(frames);
    } catch (err) {
      themeSyncing = false;
      lastSyncedHash = null;
      log(`theme send failed: ${err.message || err}`);
    }
  }

  function cleanup({ keepConfig = false } = {}) {
    if (restartTimer) { clearTimer(restartTimer); restartTimer = null; }
    if (!keepConfig) retryAttempt = 0;
    started = false;
    if (controller && typeof controller.stop === "function") { try { controller.stop(); } catch (_) {} }
    if (sidecar && typeof sidecar.stop === "function") { try { sidecar.stop(); } catch (_) {} }
    controller = null;
    sidecar = null;
    publishStatus();
  }

  function start() {
    if (started) return true;
    activeConfig = readConfig();
    if (!activeConfig.enabled) { publishStatus(); return false; }

    const python = env.CLAWD_HARDWARE_BUDDY_PYTHON || env.CLAWD_WATCH_PYTHON || "python";
    const script = resolveWatchBridgeScript(env);
    const args = [script, "--backend", "watch"];
    if (activeConfig.namePrefix) args.push("--name-prefix", activeConfig.namePrefix);
    if (activeConfig.address) args.push("--address", activeConfig.address);

    const sidecarOptions = {
      command: python,
      args,
      spawnOptions: { env: { ...process.env, PYTHONIOENCODING: "utf-8:replace" } },
      log: (level, message) => {
        if (/^sidecar exited\b/.test(String(message || ""))) {
          handleIssue({ code: "SIDECAR_EXIT", message }, { restart: true });
          return;
        }
        log(`bridge ${level}: ${message}`);
      },
      onStatus: (status) => {
        if (status && status.connected === true) {
          retryAttempt = 0;
          isConnecting = false;
        }
        if ("scanning" in status) {
          isScanning = !!status.scanning;
        }
        if (status && status.themeHash) watchThemeHash = status.themeHash;
        publishStatus();
        maybeSyncTheme();
      },
      onDevices: (items) => {
        lastDevices = Array.isArray(items) ? items : [];
        publishStatus();
      },
      onError: (err) => {
        // DISCONNECT_STUCK means the BLE link refused to close. Kill the
        // sidecar process so the OS reclaims the connection, then restart.
        if (err && (err.code === "DISCONNECT_STUCK" || (err.message && err.message.includes("DISCONNECT_STUCK")))) {
          log("watch: forcing sidecar restart due to stuck BLE link");
          cleanup({ keepConfig: true });
          setTimer(() => { if (activeConfig.enabled) start(); }, 500);
          return;
        }
        isConnecting = false;
        isScanning = false;
        handleIssue(err);
      },
      onTransportStateChanged: (state) => {
        if (state && state.connected === true) {
          retryAttempt = 0;
          lastError = null;
          isConnecting = false;
          if (controller && typeof controller.resetDedup === "function") controller.resetDedup();
        } else if (state && state.previous && state.previous.connected === true) {
          // Don't call handleIssue here — bridge handles BLE reconnect internally.
          // Adapter only restarts on SIDECAR_EXIT (process death) via log callback.
          isConnecting = false;
          themeSyncing = false;
          lastSyncedHash = null;
          watchThemeHash = null;
        }
        publishStatus();
        // Push current state FIRST (small payload, single GATT write) so the
        // watch sees the live snapshot immediately; theme transfer is queued
        // afterwards by the onStatus handler that fires right after this.
        if (controller && typeof controller.notifyStateChanged === "function") controller.notifyStateChanged();
      },
      onThemeSynced: (msg) => {
        themeSyncing = false;
        if (msg && msg.hash) watchThemeHash = msg.hash;
        log(`theme sync confirmed by bridge: ${msg && msg.hash} (${msg && msg.frames} frames)`);
        // The desktop theme may have changed again during transfer — re-check.
        maybeSyncTheme();
      },
      onApprovalResponse: (msg) => {
        if (!msg || !msg.requestId) return;
        if (!activeConfig.permissionsEnabled) return;
        const resolve = typeof options.resolvePermissionEntry === "function" ? options.resolvePermissionEntry : null;
        const getPerms = typeof options.getPendingPermissions === "function" ? options.getPendingPermissions : null;
        if (!resolve || !getPerms) return;
        const perms = getPerms();
        const match = perms.find((p) => watchApprovalId(p) === msg.requestId);
        if (match) {
          const decision = (msg.decision || "").startsWith("allow") ? "allow" : "deny";
          try { resolve(match, decision); } catch (_) {}
        }
      },
    };
    sidecar = typeof options.createSidecar === "function"
      ? options.createSidecar(sidecarOptions)
      : new WatchSidecarClient(sidecarOptions);

    controller = new WatchController({
      transport: sidecar.transport,
      getSessionSnapshot: options.getSessionSnapshot || (() => ({ sessions: [] })),
      getCurrentState: options.getCurrentState || (() => "idle"),
      getThemeFingerprint: options.getThemeFingerprint || (() => null),
      getPendingPermissions: () => activeConfig.permissionsEnabled
        ? (typeof options.getPendingPermissions === "function" ? options.getPendingPermissions() : [])
        : [],
      buildApprovalId: watchApprovalId,
      keepaliveMs: 10000,
      log: (message) => log(`controller: ${message}`),
    });

    sidecar.start();
    controller.start();
    started = true;
    log(`started namePrefix=${activeConfig.namePrefix}`);
    publishStatus();
    return true;
  }

  function stop() {
    cleanup();
    publishStatus();
  }

  function applySettingsChange(nextSettings) {
    const previous = activeConfig;
    activeConfig = readConfig();
    if (!activeConfig.enabled) { if (started) cleanup({ keepConfig: true }); publishStatus(); return; }
    if (!started) { start(); return; }
    // namePrefix is a spawn-time argument so it requires a sidecar bounce.
    const namePrefixChanged = previous.namePrefix !== activeConfig.namePrefix;
    const addressChanged = previous.address !== activeConfig.address;
    if (namePrefixChanged) { cleanup({ keepConfig: true }); start(); return; }
    // Address-only change is forwarded to the running sidecar via the connect
    // command — no process restart, so the device list / scan results in the
    // settings panel stay rendered and the user sees a connecting state
    // instead of the row blinking back to "No devices" while we respawn.
    if (addressChanged && activeConfig.address) {
      isConnecting = true;
      if (sidecar && typeof sidecar.connect === "function") sidecar.connect(activeConfig.address);
    }
    publishStatus();
  }

  function notifyStateChanged() {
    if (!started || !controller || typeof controller.notifyStateChanged !== "function") return null;
    const result = controller.notifyStateChanged();
    maybeSyncTheme();
    return result;
  }

  function notifyPermissionsChanged() {
    if (!started || !controller || typeof controller.notifyPermissionsChanged !== "function") return null;
    return controller.notifyPermissionsChanged();
  }

  function scan() {
    if (!started || !sidecar || typeof sidecar.scan !== "function") return;
    sidecar.scan();
  }

  function reconnect(address) {
    if (!started || !sidecar || typeof sidecar.reconnect !== "function") return;
    const addr = address || activeConfig.address;
    if (!addr) return;
    isConnecting = true;
    publishStatus();
    sidecar.reconnect(addr);
  }

  function connectDevice(address) {
    if (!started || !sidecar || typeof sidecar.connect !== "function") return;
    isConnecting = true;
    publishStatus();
    sidecar.connect(address);
  }

  function disconnectDevice() {
    isConnecting = false;
    if (!started || !sidecar) { publishStatus(); return; }
    if (typeof sidecar.disconnect === "function") sidecar.disconnect();
    // Optimistically flip the transport to disconnected so the UI returns
    // to the device-list / Scan branch immediately. macOS BleakClient.
    // disconnect() can take a few seconds (or hang) before Python emits
    // status=false; without this flip, Disconnect appears unresponsive.
    // If Python later reports a different state, the next status frame
    // will reconcile via publishStatus().
    if (sidecar.transport) {
      sidecar.transport.connected = false;
      sidecar.transport.secure = false;
    }
    // Reset theme-sync bookkeeping the same way onTransportStateChanged would,
    // so the next reconnect re-queries the watch hash and re-pushes if needed.
    themeSyncing = false;
    lastSyncedHash = null;
    watchThemeHash = null;
    publishStatus();
  }

  return {
    start,
    stop,
    scan,
    reconnect,
    connectDevice,
    disconnectDevice,
    applySettingsChange,
    notifyStateChanged,
    notifyPermissionsChanged,
    isEnabled: () => activeConfig.enabled,
    isStarted: () => started,
    getStatus: () => publishStatus(),
  };
}

module.exports = { createWatchAdapter, classifyWatchIssue, watchApprovalId };
