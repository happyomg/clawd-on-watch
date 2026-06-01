"use strict";

class WatchController {
  constructor(options = {}) {
    this.transport = options.transport;
    this.getCurrentState = options.getCurrentState || (() => "idle");
    this.getThemeFingerprint = options.getThemeFingerprint || (() => null);
    this.getSessionSnapshot = options.getSessionSnapshot || (() => ({ sessions: [] }));
    this.getPendingPermissions = options.getPendingPermissions || (() => []);
    this.buildApprovalId = options.buildApprovalId || ((p) => p.requestId || p.id || "");
    this.getDoNotDisturb = options.getDoNotDisturb || (() => false);
    this.keepaliveMs = options.keepaliveMs || 10000;
    this.log = options.log || (() => {});

    this._interval = null;
    this._lastPayload = "";
    this._started = false;
  }

  start() {
    if (this._started) return;
    this._started = true;
    this._pushState();
    this._interval = setInterval(() => this._pushState(), this.keepaliveMs);
  }

  stop() {
    this._started = false;
    if (this._interval) clearInterval(this._interval);
    this._interval = null;
  }

  notifyStateChanged() {
    if (!this._started) return null;
    return this._pushState();
  }

  resetDedup() {
    this._lastPayload = "";
  }

  notifyPermissionsChanged() {
    if (!this._started) return null;
    return this._pushPermissions();
  }

  _buildCompactPayload() {
    const state = this.getCurrentState();
    const snapshot = this.getSessionSnapshot();
    const sessions = snapshot && Array.isArray(snapshot.sessions) ? snapshot.sessions : [];
    const nonIdle = sessions.filter((s) => s.state !== "idle" && s.state !== "sleeping" && !s.headless);
    // Theme-agnostic payload: the watch resolves `s` + `n` to a concrete SVG
    // via its local theme manifest, so we no longer send a filename. `th` is
    // the active theme fingerprint, used by the watch to detect a stale theme
    // and trigger a sync. Omitted when no theme is active.
    const payload = { s: state, n: nonIdle.length };
    const th = this.getThemeFingerprint();
    if (th) payload.th = th;
    return payload;
  }

  _pushState() {
    if (!this.transport || !this.transport.connected) return null;
    try {
      const payload = this._buildCompactPayload();
      const encoded = JSON.stringify(payload);
      if (encoded === this._lastPayload) return null;
      this._lastPayload = encoded;
      this.transport.send(payload);
      return true;
    } catch (err) {
      this.log(`state push failed: ${err.message || err}`);
      return false;
    }
  }

  _pushPermissions() {
    if (!this.transport || !this.transport.connected) return null;
    try {
      const perms = this.getPendingPermissions();
      if (!perms || !perms.length) return null;
      for (const perm of perms) {
        const toolInput = perm.toolInput && typeof perm.toolInput === "object"
          ? (perm.toolInput.command || JSON.stringify(perm.toolInput)).slice(0, 200)
          : String(perm.toolInput || "").slice(0, 200);
        const msg = {
          type: "approval_request",
          requestId: this.buildApprovalId(perm),
          sessionId: perm.sessionId || "",
          tool: perm.toolName || perm.tool || "",
          command: toolInput,
          risk: perm.risk || "medium",
        };
        if (perm.isElicitation && perm.toolInput && Array.isArray(perm.toolInput.questions)) {
          msg.questions = perm.toolInput.questions.map((q) => ({
            q: q.question || "",
            opts: Array.isArray(q.options) ? q.options.map((o) => o.label || "") : [],
          }));
        }
        this.transport.send(msg);
      }
      return true;
    } catch (err) {
      this.log(`permission push failed: ${err.message || err}`);
      return false;
    }
  }
}

module.exports = { WatchController };
