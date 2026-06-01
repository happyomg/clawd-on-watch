"use strict";

const { describe, it, afterEach } = require("node:test");
const assert = require("node:assert");
const { createWatchAdapter, classifyWatchIssue, watchApprovalId } = require("../src/watch-adapter");

function makeFakeSidecar() {
  let opts = null;
  const transport = { connected: false, secure: false, sent: [], send(p) { this.sent.push(p); } };
  const factory = (sidecarOptions) => {
    opts = sidecarOptions;
    return { transport, start() {}, stop() {} };
  };
  return { factory, get opts() { return opts; }, transport };
}

function baseDeps(extra = {}) {
  const timers = [];
  const adapterOpts = {
    env: {},
    getSettings: () => ({ enabled: true, permissionsEnabled: true, namePrefix: "Clawd", address: "" }),
    setTimeout: (fn, ms) => { const id = { fn, ms }; timers.push(id); return id; },
    clearTimeout: (id) => { const i = timers.indexOf(id); if (i >= 0) timers.splice(i, 1); },
    now: () => 1000,
    log: () => {},
    onStatusChanged: () => {},
    getSessionSnapshot: () => ({ sessions: [] }),
    getCurrentState: () => "idle",
    getCurrentSvg: () => null,
    ...extra,
  };
  return { adapterOpts, timers };
}

let live = null;
afterEach(() => { if (live) { try { live.stop(); } catch (_) {} live = null; } });

describe("watch-adapter approval round-trip", () => {
  it("should resolve the real pending entry with allow when ids match", () => {
    const fake = makeFakeSidecar();
    const perm = { sessionId: "abcdefghXYZ", toolName: "Bash", createdAt: 42 };
    const calls = [];
    const { adapterOpts } = baseDeps({
      createSidecar: fake.factory,
      getPendingPermissions: () => [perm],
      resolvePermissionEntry: (entry, decision) => calls.push([entry, decision]),
    });
    live = createWatchAdapter(adapterOpts);
    live.start();

    fake.opts.onApprovalResponse({ requestId: watchApprovalId(perm), decision: "allow" });

    assert.strictEqual(calls.length, 1);
    assert.strictEqual(calls[0][0], perm);
    assert.strictEqual(calls[0][1], "allow");
  });

  it("should map unknown decision to deny", () => {
    const fake = makeFakeSidecar();
    const perm = { sessionId: "s", toolName: "Edit", createdAt: 7 };
    const calls = [];
    const { adapterOpts } = baseDeps({
      createSidecar: fake.factory,
      getPendingPermissions: () => [perm],
      resolvePermissionEntry: (e, d) => calls.push(d),
    });
    live = createWatchAdapter(adapterOpts); live.start();
    fake.opts.onApprovalResponse({ requestId: watchApprovalId(perm), decision: "whatever" });
    assert.deepStrictEqual(calls, ["deny"]);
  });

  it("should NOT resolve when permissionsEnabled is false", () => {
    const fake = makeFakeSidecar();
    const perm = { sessionId: "s", toolName: "Bash", createdAt: 1 };
    const calls = [];
    const { adapterOpts } = baseDeps({
      getSettings: () => ({ enabled: true, permissionsEnabled: false, namePrefix: "Clawd", address: "" }),
      createSidecar: fake.factory,
      getPendingPermissions: () => [perm],
      resolvePermissionEntry: () => calls.push(1),
    });
    live = createWatchAdapter(adapterOpts); live.start();
    fake.opts.onApprovalResponse({ requestId: watchApprovalId(perm), decision: "allow" });
    assert.strictEqual(calls.length, 0);
  });

  it("should NOT resolve when requestId matches no pending entry", () => {
    const fake = makeFakeSidecar();
    const calls = [];
    const { adapterOpts } = baseDeps({
      createSidecar: fake.factory,
      getPendingPermissions: () => [{ sessionId: "s", toolName: "Bash", createdAt: 1 }],
      resolvePermissionEntry: () => calls.push(1),
    });
    live = createWatchAdapter(adapterOpts); live.start();
    fake.opts.onApprovalResponse({ requestId: "nope:nope:0", decision: "allow" });
    assert.strictEqual(calls.length, 0);
  });

  it("should not push permissions when permissionsEnabled is false", () => {
    const fake = makeFakeSidecar();
    const perm = { sessionId: "s", toolName: "Bash", toolInput: { command: "ls" }, createdAt: 1 };
    const { adapterOpts } = baseDeps({
      getSettings: () => ({ enabled: true, permissionsEnabled: false, namePrefix: "Clawd", address: "" }),
      createSidecar: fake.factory,
      getPendingPermissions: () => [perm],
    });
    live = createWatchAdapter(adapterOpts); live.start();
    fake.transport.connected = true;
    live.notifyPermissionsChanged();
    const approvals = fake.transport.sent.filter((m) => m.type === "approval_request");
    assert.strictEqual(approvals.length, 0);
  });
});

describe("watch-adapter restart/backoff", () => {
  it("should schedule restart on sidecar exit", () => {
    const fake = makeFakeSidecar();
    const { adapterOpts, timers } = baseDeps({ createSidecar: fake.factory });
    live = createWatchAdapter(adapterOpts); live.start();

    fake.opts.log("info", "sidecar exited code=1 signal=null");
    assert.strictEqual(timers.length, 1);
    assert.strictEqual(timers[0].ms, 15000);
  });

  it("should reset retryAttempt on successful connection", () => {
    const fake = makeFakeSidecar();
    const statuses = [];
    const { adapterOpts } = baseDeps({
      createSidecar: fake.factory,
      onStatusChanged: (s) => statuses.push(s),
    });
    live = createWatchAdapter(adapterOpts); live.start();

    // Crash increments retry
    fake.opts.log("info", "sidecar exited code=1 signal=null");
    const afterCrash = statuses[statuses.length - 1];
    assert.ok(afterCrash.retryAttempt >= 1);

    // Connect resets
    fake.opts.onStatus({ connected: true });
    const afterConnect = statuses[statuses.length - 1];
    assert.strictEqual(afterConnect.retryAttempt, 0);
  });
});

describe("classifyWatchIssue", () => {
  it("should classify MISSING_BLEAK as non-retryable", () => {
    const r = classifyWatchIssue({ code: "MISSING_BLEAK" });
    assert.strictEqual(r.category, "missing_bleak");
    assert.strictEqual(r.retryable, false);
  });

  it("should classify DISCONNECTED as retryable", () => {
    const r = classifyWatchIssue({ code: "DISCONNECTED" });
    assert.strictEqual(r.category, "disconnected");
    assert.strictEqual(r.retryable, true);
  });

  it("should classify ENOENT as python_missing", () => {
    const r = classifyWatchIssue({ code: "ENOENT" });
    assert.strictEqual(r.category, "python_missing");
    assert.strictEqual(r.retryable, false);
  });

  it("should classify SIDECAR_EXIT as retryable", () => {
    const r = classifyWatchIssue({ code: "SIDECAR_EXIT" });
    assert.strictEqual(r.category, "sidecar_exited");
    assert.strictEqual(r.retryable, true);
  });

  it("should classify unknown codes as watch_error", () => {
    const r = classifyWatchIssue({ code: "SOMETHING" });
    assert.strictEqual(r.category, "watch_error");
    assert.strictEqual(r.retryable, true);
  });
});

describe("watchApprovalId", () => {
  it("should generate deterministic id from sessionId + toolName + createdAt", () => {
    const id = watchApprovalId({ sessionId: "abcdefgh12345678", toolName: "Bash", createdAt: 42 });
    assert.strictEqual(id, "12345678:Bash:42");
  });

  it("should handle missing fields gracefully", () => {
    const id = watchApprovalId({});
    assert.strictEqual(id, "::0");
  });
});

describe("watch-adapter permission filtering (main.js wiring contract)", () => {
  // main.js filters getPendingPermissions before passing to the adapter.
  // This test documents the contract: ExitPlanMode, AskUserQuestion, elicitations,
  // and notification-only entries must never reach the watch.
  function applyMainFilter(perms) {
    return perms.filter(
      p => !p.isElicitation
        && !p.isCodexNotify
        && !p.isKimiNotify
        && !p.isHardwareBuddyTest
        && p.toolName !== "ExitPlanMode"
        && p.toolName !== "AskUserQuestion"
    );
  }

  it("should filter out ExitPlanMode", () => {
    const perms = [
      { sessionId: "s1", toolName: "ExitPlanMode", createdAt: 1 },
      { sessionId: "s2", toolName: "Bash", createdAt: 2 },
    ];
    const filtered = applyMainFilter(perms);
    assert.strictEqual(filtered.length, 1);
    assert.strictEqual(filtered[0].toolName, "Bash");
  });

  it("should filter out AskUserQuestion (elicitation via toolName)", () => {
    const perms = [
      { sessionId: "s1", toolName: "AskUserQuestion", createdAt: 1 },
      { sessionId: "s2", toolName: "Edit", createdAt: 2 },
    ];
    const filtered = applyMainFilter(perms);
    assert.strictEqual(filtered.length, 1);
    assert.strictEqual(filtered[0].toolName, "Edit");
  });

  it("should filter out entries with isElicitation flag", () => {
    const perms = [
      { sessionId: "s1", toolName: "AskUserQuestion", isElicitation: true, createdAt: 1 },
      { sessionId: "s2", toolName: "Write", createdAt: 2 },
    ];
    const filtered = applyMainFilter(perms);
    assert.strictEqual(filtered.length, 1);
    assert.strictEqual(filtered[0].toolName, "Write");
  });

  it("should filter out codex and kimi notification entries", () => {
    const perms = [
      { sessionId: "s1", toolName: "CodexExec", isCodexNotify: true, createdAt: 1 },
      { sessionId: "s2", toolName: "KimiPermission", isKimiNotify: true, createdAt: 2 },
      { sessionId: "s3", toolName: "Bash", createdAt: 3 },
    ];
    const filtered = applyMainFilter(perms);
    assert.strictEqual(filtered.length, 1);
    assert.strictEqual(filtered[0].toolName, "Bash");
  });

  it("should pass through normal permission tools", () => {
    const perms = [
      { sessionId: "s1", toolName: "Bash", createdAt: 1 },
      { sessionId: "s2", toolName: "Edit", createdAt: 2 },
      { sessionId: "s3", toolName: "Write", createdAt: 3 },
    ];
    const filtered = applyMainFilter(perms);
    assert.strictEqual(filtered.length, 3);
  });

  it("should verify filtered perms are not pushed to watch", () => {
    const fake = makeFakeSidecar();
    const filteredPerms = applyMainFilter([
      { sessionId: "s1", toolName: "ExitPlanMode", createdAt: 1 },
      { sessionId: "s2", toolName: "AskUserQuestion", isElicitation: true, createdAt: 2 },
      { sessionId: "s3", toolName: "Bash", toolInput: { command: "ls" }, createdAt: 3 },
    ]);
    const { adapterOpts } = baseDeps({
      createSidecar: fake.factory,
      getPendingPermissions: () => filteredPerms,
    });
    live = createWatchAdapter(adapterOpts); live.start();
    fake.transport.connected = true;
    live.notifyPermissionsChanged();
    const approvals = fake.transport.sent.filter((m) => m.type === "approval_request");
    assert.strictEqual(approvals.length, 1);
    assert.strictEqual(approvals[0].tool, "Bash");
  });
});

describe("watch-adapter theme sync", () => {
  function makeThemeSidecar() {
    let opts = null;
    const transport = { connected: false, secure: false, sent: [], send(p) { this.sent.push(p); } };
    const themeSends = [];
    const factory = (sidecarOptions) => {
      opts = sidecarOptions;
      return { transport, start() {}, stop() {}, sendThemeData: (frames) => themeSends.push(frames) };
    };
    return { factory, get opts() { return opts; }, transport, themeSends };
  }

  const bundle = {
    name: "calico",
    hash: "b7e2d4",
    stateMap: { idle: ["calico-idle.svg"] },
    files: ["calico-idle.svg"],
    fileData: { "calico-idle.svg": Buffer.from("<svg/>", "utf8") },
  };

  it("pushes the theme over CWD5 when the watch hash differs", () => {
    const fake = makeThemeSidecar();
    const { adapterOpts } = baseDeps({
      createSidecar: fake.factory,
      getThemeFingerprint: () => "b7e2d4",
      getThemeBundle: () => bundle,
    });
    live = createWatchAdapter(adapterOpts);
    live.start();
    fake.transport.connected = true;

    fake.opts.onStatus({ connected: true, themeHash: "79c952" }); // watch on a different theme

    assert.strictEqual(fake.themeSends.length, 1);
    const frames = fake.themeSends[0];
    assert.strictEqual(frames[0].t, "manifest");
    assert.strictEqual(frames[frames.length - 1].t, "done");
    assert.strictEqual(frames[frames.length - 1].hash, "b7e2d4");
  });

  it("does not push when the watch already matches", () => {
    const fake = makeThemeSidecar();
    const { adapterOpts } = baseDeps({
      createSidecar: fake.factory,
      getThemeFingerprint: () => "b7e2d4",
      getThemeBundle: () => bundle,
    });
    live = createWatchAdapter(adapterOpts);
    live.start();
    fake.transport.connected = true;

    fake.opts.onStatus({ connected: true, themeHash: "b7e2d4" });

    assert.strictEqual(fake.themeSends.length, 0);
  });

  it("does not re-push the same theme while a transfer is in flight", () => {
    const fake = makeThemeSidecar();
    const { adapterOpts } = baseDeps({
      createSidecar: fake.factory,
      getThemeFingerprint: () => "b7e2d4",
      getThemeBundle: () => bundle,
    });
    live = createWatchAdapter(adapterOpts);
    live.start();
    fake.transport.connected = true;

    fake.opts.onStatus({ connected: true, themeHash: "79c952" });
    fake.opts.onStatus({ connected: true, themeHash: "79c952" }); // duplicate status
    assert.strictEqual(fake.themeSends.length, 1);

    // Confirmation updates the watch hash → now in sync, no further push.
    fake.opts.onThemeSynced({ hash: "b7e2d4", frames: fake.themeSends[0].length });
    fake.opts.onStatus({ connected: true, themeHash: "b7e2d4" });
    assert.strictEqual(fake.themeSends.length, 1);
  });

  it("does not push when no theme bundle is available", () => {
    const fake = makeThemeSidecar();
    const { adapterOpts } = baseDeps({
      createSidecar: fake.factory,
      getThemeFingerprint: () => "b7e2d4",
      getThemeBundle: () => null,
    });
    live = createWatchAdapter(adapterOpts);
    live.start();
    fake.transport.connected = true;
    fake.opts.onStatus({ connected: true, themeHash: "79c952" });
    assert.strictEqual(fake.themeSends.length, 0);
  });
});
