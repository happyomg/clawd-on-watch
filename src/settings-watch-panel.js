"use strict";

(function initSettingsWatchPanel(root) {

  function build(core, options = {}) {
    const helpers = core.helpers;
    const activeTabId = options.activeTabId || "";
    ensureWatchStatusListener(core, activeTabId);
    return helpers.buildCollapsibleGroup({
      id: options.id || "watch",
      headerContent: buildHeader(core),
      defaultCollapsed: options.defaultCollapsed !== false,
      className: [options.className || "", "watch-collapsible"].join(" ").trim(),
      children: [buildOptionList([
        buildStatusRow(core),
        buildSwitchRow(core, "enabled", "Enable", "Connect to a Wear OS watch via BLE"),
        buildDeviceListRow(core),
        buildSwitchRow(core, "permissionsEnabled", "Approval on Watch", "Allow approving tool requests from the watch",
          { disabled: !getConfig(core.state).enabled }),
      ])],
    });
  }

  function t(core, key) { return core.helpers.t(key) || key; }

  function getConfig(state) {
    const snap = state.snapshot || {};
    const current = snap.watch && typeof snap.watch === "object" ? snap.watch : {};
    return {
      enabled: current.enabled === true,
      address: typeof current.address === "string" ? current.address : "",
      namePrefix: typeof current.namePrefix === "string" && current.namePrefix.trim() ? current.namePrefix : "Clawd",
      permissionsEnabled: current.permissionsEnabled === true,
    };
  }

  function ensureWatchStatusListener(core, activeTabId) {
    const runtime = core.runtime || (core.runtime = {});
    if (runtime.watchSettingsListenerInstalled) return;
    runtime.watchSettingsListenerInstalled = true;
    runtime.watchStatus = runtime.watchStatus || null;
    runtime.watchPendingConnect = runtime.watchPendingConnect || null;
    const rerender = () => {
      if (!activeTabId || core.state.activeTab === activeTabId) core.ops.requestRender({ content: true });
    };
    const applyStatus = (s) => {
      runtime.watchStatus = s || null;
      // Clear the local "connecting intent" once the bridge confirms connect,
      // surfaces an error, or the address moves away from what we requested.
      // Without this, the UI may briefly skip the connecting frame entirely
      // because Python's emit_status(true) lands in the same render frame as
      // the publishStatus(connecting=true) that fired moments earlier.
      const pending = runtime.watchPendingConnect;
      if (pending) {
        if (s && s.connected && s.address && s.address === pending.address) runtime.watchPendingConnect = null;
        else if (s && s.lastError && s.lastError.at && s.lastError.at >= pending.startedAt) runtime.watchPendingConnect = null;
        else if (s && s.address && s.address !== pending.address) runtime.watchPendingConnect = null;
        else if (Date.now() - pending.startedAt > 30000) runtime.watchPendingConnect = null;
      }
      rerender();
    };
    if (window.settingsAPI && typeof window.settingsAPI.getWatchStatus === "function") {
      window.settingsAPI.getWatchStatus().then(applyStatus).catch(() => {});
    }
    if (window.settingsAPI && typeof window.settingsAPI.onWatchStatusChanged === "function") {
      window.settingsAPI.onWatchStatusChanged(applyStatus);
    }
  }

  function updateConfig(core, partial) {
    if (!window.settingsAPI || typeof window.settingsAPI.update !== "function") return Promise.resolve({ status: "error" });
    const next = { ...getConfig(core.state), ...partial };
    return window.settingsAPI.update("watch", next).then((result) => {
      if (!result || result.status !== "ok") {
        core.ops.showToast("Save failed: " + ((result && result.message) || "unknown"), { error: true });
      }
      return result;
    }).catch((err) => {
      core.ops.showToast("Save failed: " + (err && err.message), { error: true });
      return { status: "error" };
    });
  }

  function statusKind(core) {
    const status = core.runtime && core.runtime.watchStatus;
    const pending = core.runtime && core.runtime.watchPendingConnect;
    const config = getConfig(core.state);
    if (!config.enabled) return "off";
    if (status && status.lastError) return "error";
    if (status && status.connected) return "connected";
    if ((status && status.connecting) || pending) return "connecting";
    if (status && status.scanning) return "scanning";
    // The sidecar is alive but not actively connecting/scanning — it's just
    // waiting for the user to pick a device. "Searching..." implies an
    // automatic scan in progress, which is misleading; treat it as idle.
    return "idle";
  }

  function statusText(kind) {
    return { off: "Off", error: "Error", connected: "Connected", connecting: "Connecting...", scanning: "Scanning...", idle: "Ready" }[kind] || kind;
  }

  function buildHeader(core) {
    const wrap = document.createElement("div");
    wrap.className = "tg-approval-channel-header";
    const name = document.createElement("span");
    name.className = "tg-approval-channel-name";
    name.textContent = "Watch";
    wrap.appendChild(name);
    const kind = statusKind(core);
    const badge = document.createElement("span");
    const badgeClass = { connected: "tg-approval-badge-running", connecting: "tg-approval-badge-starting", searching: "tg-approval-badge-starting", error: "tg-approval-badge-failed" }[kind] || "tg-approval-badge-incomplete";
    badge.className = `tg-approval-channel-badge ${badgeClass}`;
    const dot = document.createElement("span");
    dot.className = "tg-approval-channel-badge-dot";
    badge.appendChild(dot);
    const badgeText = document.createElement("span");
    badgeText.textContent = statusText(kind);
    badge.appendChild(badgeText);
    wrap.appendChild(badge);
    return wrap;
  }

  function buildStatusRow(core) {
    const status = core.runtime && core.runtime.watchStatus;
    const pending = core.runtime && core.runtime.watchPendingConnect;
    const config = getConfig(core.state);
    const row = document.createElement("div");
    row.className = "row";
    const kind = statusKind(core);
    let detail = "Watch companion for Clawd on Desk";
    if (!config.enabled) detail = "Enable to connect a Wear OS watch";
    else if (status && status.lastError) detail = status.lastError.hint || status.lastError.message || "Error";
    else if (status && status.connected) detail = "Connected to watch";
    else if ((status && status.connecting) || pending) detail = "Connecting to watch...";
    row.innerHTML = `<div class="row-text"><span class="row-label">Status</span><span class="row-desc"></span></div>` +
      `<div class="row-control"><span class="hardware-buddy-status-badge hardware-buddy-status-${kind}"></span></div>`;
    row.querySelector(".row-desc").textContent = detail;
    row.querySelector(".hardware-buddy-status-badge").textContent = statusText(kind);

    const isMissingBleak = status && status.lastError && status.lastError.category === "missing_bleak";
    if (isMissingBleak) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.textContent = "Install bleak";
      btn.style.cssText = "margin-top:6px;padding:4px 12px;font-size:12px;border-radius:6px;border:1px solid #555;background:#333;color:#eee;cursor:pointer;";
      btn.addEventListener("click", () => {
        btn.disabled = true;
        btn.textContent = "Installing bleak...";
        window.settingsAPI.command("watch.installBleak").then((result) => {
          if (result && result.status === "ok") {
            btn.textContent = "Installed! Connecting...";
            btn.style.cssText += "border-color:#4ADE80;background:#1a3a1a;";
            core.ops.showToast("bleak installed — connecting to watch...", { error: false });
            window.settingsAPI.command("watch.restart");
            // Delay the rerender so user sees the success state
            setTimeout(function() {
              if (core.runtime) core.runtime.watchStatus = { started: true, connected: false, lastError: null };
              core.ops.requestRender({ content: true });
            }, 2000);
          } else {
            btn.textContent = "Install failed";
            btn.style.cssText += "border-color:#F87171;";
            btn.disabled = false;
            core.ops.showToast("bleak install failed: " + ((result && result.message) || ""), { error: true });
          }
        }).catch(function(err) {
          btn.textContent = "Install error";
          btn.disabled = false;
          core.ops.showToast("Install error: " + (err && err.message || ""), { error: true });
        });
      });
      row.querySelector(".row-text").appendChild(btn);
    }
    return row;
  }

  function buildDeviceListRow(core) {
    const status = core.runtime && core.runtime.watchStatus;
    const config = getConfig(core.state);
    const row = document.createElement("div");
    row.className = "row";
    if (!config.enabled) return row;

    const pending = core.runtime && core.runtime.watchPendingConnect;
    const devices = (status && Array.isArray(status.devices)) ? status.devices : [];
    const connected = status && status.connected;
    const connecting = (status && status.connecting) || !!pending;
    const currentAddr = (pending && pending.address) || config.address;
    var btnStyle = "padding:4px 12px;font-size:12px;border-radius:6px;border:1px solid #555;background:#333;color:#eee;cursor:pointer;min-height:auto;width:auto;";

    row.innerHTML = '<div class="row-text"><span class="row-label">Device</span><span class="row-desc"></span></div>' +
      '<div class="row-control"></div>';

    var controlDiv = row.querySelector(".row-control");

    // pending implies the user just clicked a device; even if status.connected
    // is still true (we're still attached to the previous device while the
    // bridge tears it down to switch), surface the connecting branch so the
    // row reflects the user's intent and offers Cancel.
    if (pending || (connecting && !connected)) {
      var connDevice = devices.find(function(d) { return d.address === currentAddr; });
      var label = (pending && pending.name) || (connDevice ? connDevice.name : (currentAddr ? currentAddr.slice(0, 16) : "watch"));
      row.querySelector(".row-desc").textContent = "Connecting to " + label + "...";
      var cancelBtn = document.createElement("button");
      cancelBtn.type = "button";
      cancelBtn.textContent = "Cancel";
      cancelBtn.style.cssText = btnStyle + "border-color:#888;";
      cancelBtn.addEventListener("click", function() {
        cancelBtn.disabled = true;
        if (core.runtime) core.runtime.watchPendingConnect = null;
        if (window.settingsAPI && typeof window.settingsAPI.command === "function") {
          window.settingsAPI.command("watch.disconnect");
        }
        core.ops.requestRender({ content: true });
      });
      controlDiv.appendChild(cancelBtn);
    } else if (connected && currentAddr) {
      var connName = devices.find(function(d) { return d.address === currentAddr; });
      row.querySelector(".row-desc").textContent = "Connected: " + (connName ? connName.name : currentAddr.slice(0, 16));

      var disconnectBtn = document.createElement("button");
      disconnectBtn.type = "button";
      disconnectBtn.textContent = "Disconnect";
      disconnectBtn.style.cssText = btnStyle + "border-color:#F87171;";
      disconnectBtn.addEventListener("click", function() {
        disconnectBtn.textContent = "Disconnecting...";
        disconnectBtn.disabled = true;
        if (core.runtime) core.runtime.watchPendingConnect = null;
        if (window.settingsAPI && typeof window.settingsAPI.command === "function") {
          window.settingsAPI.command("watch.disconnect");
        }
        core.ops.requestRender({ content: true });
      });
      controlDiv.appendChild(disconnectBtn);
    } else {
      var scanning = status && status.scanning;
      if (scanning) {
        row.querySelector(".row-desc").textContent = "Scanning for nearby watches...";
      } else if (devices.length > 0) {
        row.querySelector(".row-desc").textContent = devices.length + " device(s) found \u2014 select one:";
      } else if (config.address) {
        // We have a saved address from a previous connection — offer direct
        // reconnect so the user doesn't need to scan every time.
        row.querySelector(".row-desc").textContent = "Last device available";
      } else {
        row.querySelector(".row-desc").textContent = "No devices found";
      }

      // Reconnect button — direct connection to saved address (no scan needed)
      if (config.address && !scanning) {
        var reconnectBtn = document.createElement("button");
        reconnectBtn.type = "button";
        reconnectBtn.textContent = "Reconnect";
        reconnectBtn.style.cssText = btnStyle + "border-color:#4ADE80;margin-right:6px;";
        reconnectBtn.addEventListener("click", function() {
          reconnectBtn.textContent = "Connecting...";
          reconnectBtn.disabled = true;
          if (core.runtime) core.runtime.watchPendingConnect = { address: config.address, name: "", startedAt: Date.now() };
          if (window.settingsAPI && typeof window.settingsAPI.command === "function") {
            window.settingsAPI.command("watch.reconnect", { address: config.address });
          }
          core.ops.requestRender({ content: true });
        });
        controlDiv.appendChild(reconnectBtn);
      }
    
      var scanBtn = document.createElement("button");
      scanBtn.type = "button";
      scanBtn.textContent = scanning ? "Scanning..." : "Scan";
      scanBtn.disabled = !!scanning;
      scanBtn.style.cssText = btnStyle;
      scanBtn.addEventListener("click", function() {
        scanBtn.textContent = "Scanning...";
        scanBtn.disabled = true;
        window.settingsAPI.command("watch.scan");
      });
      controlDiv.appendChild(scanBtn);

      if (devices.length > 0) {
        var list = document.createElement("div");
        list.style.cssText = "margin-top:8px;";
        for (var i = 0; i < devices.length; i++) {
          (function(dev) {
            var item = document.createElement("button");
            item.type = "button";
            item.textContent = (dev.name || "Unknown") + "  RSSI " + (dev.rssi || "?");
            item.style.cssText = "display:block;width:100%;margin-bottom:4px;padding:6px 10px;font-size:11px;border-radius:6px;border:1px solid #444;background:#222;color:#ddd;cursor:pointer;text-align:left;min-height:auto;";
            item.addEventListener("click", function() {
              // Local connecting intent: keeps the UI in the "connecting"
              // branch even if Python's connected status arrives in the same
              // render frame as the initial publishStatus(connecting=true).
              if (core.runtime) core.runtime.watchPendingConnect = { address: dev.address, name: dev.name || "", startedAt: Date.now() };
              // Persist the address (so it survives restarts) and tell the
              // running sidecar to connect. The adapter's applySettingsChange
              // forwards address-only updates as a connect command instead of
              // bouncing the sidecar, so this row stays rendered while the
              // "connecting" status from the bridge updates the badge.
              updateConfig(core, { address: dev.address });
              if (window.settingsAPI && typeof window.settingsAPI.command === "function") {
                window.settingsAPI.command("watch.connect", { address: dev.address });
              }
              core.ops.requestRender({ content: true });
            });
            list.appendChild(item);
          })(devices[i]);
        }
        row.querySelector(".row-text").appendChild(list);
      }
    }

    return row;
  }

  function buildSwitchRow(core, field, label, desc, opts = {}) {
    const config = getConfig(core.state);
    const row = document.createElement("div");
    row.className = "row";
    row.innerHTML = `<div class="row-text"><span class="row-label"></span><span class="row-desc"></span></div>` +
      `<div class="row-control"><div class="switch" role="switch" tabindex="0"></div></div>`;
    row.querySelector(".row-label").textContent = label;
    row.querySelector(".row-desc").textContent = desc;
    const sw = row.querySelector(".switch");
    core.helpers.setSwitchVisual(sw, config[field] === true, { pending: false });
    if (opts.disabled) { sw.classList.add("disabled"); sw.setAttribute("aria-disabled", "true"); sw.tabIndex = -1; }
    const run = () => {
      if (sw.classList.contains("disabled") || sw.classList.contains("pending")) return;
      const next = !(getConfig(core.state)[field] === true);
      core.helpers.setSwitchVisual(sw, next, { pending: true });
      updateConfig(core, { [field]: next }).then((r) => {
        core.helpers.setSwitchVisual(sw, r && r.status === "ok" ? next : getConfig(core.state)[field] === true, { pending: false });
      });
    };
    sw.addEventListener("click", run);
    sw.addEventListener("keydown", (ev) => { if (ev.key === " " || ev.key === "Enter") { ev.preventDefault(); run(); } });
    return row;
  }

  function buildTextRow(core, field, label, desc, opts = {}) {
    const config = getConfig(core.state);
    const row = document.createElement("div");
    row.className = "row";
    row.innerHTML = `<div class="row-text"><span class="row-label"></span><span class="row-desc"></span></div>` +
      `<div class="row-control hardware-buddy-text-control"><input type="text" class="hardware-buddy-text-input" /></div>`;
    row.querySelector(".row-label").textContent = label;
    row.querySelector(".row-desc").textContent = desc;
    const input = row.querySelector("input");
    input.value = config[field] || "";
    input.placeholder = opts.placeholder || "";
    input.maxLength = opts.maxLength || 120;
    let last = input.value;
    function commit() {
      const v = input.value.trim();
      if (v === last) return;
      input.classList.add("pending");
      updateConfig(core, { [field]: v }).then((r) => { input.classList.remove("pending"); if (r && r.status === "ok") last = v; else input.value = last; });
    }
    input.addEventListener("blur", commit);
    input.addEventListener("keydown", (ev) => { if (ev.key === "Enter") { ev.preventDefault(); commit(); input.blur(); } if (ev.key === "Escape") { input.value = last; input.blur(); } });
    return row;
  }

  function buildOptionList(rows) {
    const list = document.createElement("div");
    list.className = "settings-option-list";
    for (const row of rows) { row.classList.add("settings-option-item"); list.appendChild(row); }
    return list;
  }

  root.ClawdSettingsWatchPanel = { build };
})(globalThis);
