"use strict";

const { describe, it } = require("node:test");
const assert = require("node:assert");
const { WatchController } = require("../src/watch-controller");

function createFakeTransport() {
  return {
    connected: false,
    secure: false,
    sent: [],
    send(payload) { this.sent.push(JSON.parse(JSON.stringify(payload))); },
  };
}

describe("WatchController", () => {
  it("should not push when transport is disconnected", () => {
    const transport = createFakeTransport();
    const ctrl = new WatchController({ transport, keepaliveMs: 100000 });
    ctrl.start();
    assert.strictEqual(transport.sent.length, 0);
    ctrl.stop();
  });

  it("should push theme-agnostic compact payload {s,n,th} when connected", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    const ctrl = new WatchController({
      transport,
      getCurrentState: () => "working",
      getThemeFingerprint: () => "a3f8c1",
      getSessionSnapshot: () => ({
        sessions: [
          { state: "working", headless: false },
          { state: "idle", headless: false },
        ],
      }),
      keepaliveMs: 100000,
    });
    ctrl.start();
    assert.strictEqual(transport.sent.length, 1);
    assert.strictEqual(transport.sent[0].s, "working");
    assert.strictEqual(transport.sent[0].n, 1);
    assert.strictEqual(transport.sent[0].th, "a3f8c1");
    // The desktop no longer dictates a filename — the watch resolves it.
    assert.strictEqual("svg" in transport.sent[0], false);
    ctrl.stop();
  });

  it("should omit th when no theme fingerprint is available", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    const ctrl = new WatchController({
      transport,
      getCurrentState: () => "idle",
      getThemeFingerprint: () => null,
      getSessionSnapshot: () => ({ sessions: [] }),
      keepaliveMs: 100000,
    });
    ctrl.start();
    assert.strictEqual(transport.sent.length, 1);
    assert.strictEqual("th" in transport.sent[0], false);
    ctrl.stop();
  });

  it("should re-push when only the theme fingerprint changes", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    let th = "a3f8c1";
    const ctrl = new WatchController({
      transport,
      getCurrentState: () => "idle",
      getThemeFingerprint: () => th,
      getSessionSnapshot: () => ({ sessions: [] }),
      keepaliveMs: 100000,
    });
    ctrl.start();
    assert.strictEqual(transport.sent.length, 1);
    th = "b7e2d4";
    ctrl.notifyStateChanged();
    assert.strictEqual(transport.sent.length, 2);
    assert.strictEqual(transport.sent[1].th, "b7e2d4");
    ctrl.stop();
  });

  it("should deduplicate identical payloads", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    const ctrl = new WatchController({
      transport,
      getCurrentState: () => "idle",
      getCurrentSvg: () => null,
      getSessionSnapshot: () => ({ sessions: [] }),
      keepaliveMs: 100000,
    });
    ctrl.start();
    ctrl.notifyStateChanged();
    ctrl.notifyStateChanged();
    assert.strictEqual(transport.sent.length, 1);
    ctrl.stop();
  });

  it("should push again when state changes", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    let state = "idle";
    const ctrl = new WatchController({
      transport,
      getCurrentState: () => state,
      getCurrentSvg: () => null,
      getSessionSnapshot: () => ({ sessions: [] }),
      keepaliveMs: 100000,
    });
    ctrl.start();
    assert.strictEqual(transport.sent.length, 1);
    state = "working";
    ctrl.notifyStateChanged();
    assert.strictEqual(transport.sent.length, 2);
    assert.strictEqual(transport.sent[1].s, "working");
    ctrl.stop();
  });

  it("should count only non-idle non-headless sessions", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    const ctrl = new WatchController({
      transport,
      getCurrentState: () => "working",
      getCurrentSvg: () => null,
      getSessionSnapshot: () => ({
        sessions: [
          { state: "working", headless: false },
          { state: "idle", headless: false },
          { state: "thinking", headless: true },
          { state: "sleeping", headless: false },
        ],
      }),
      keepaliveMs: 100000,
    });
    ctrl.start();
    assert.strictEqual(transport.sent[0].n, 1);
    ctrl.stop();
  });

  it("should push approval requests directly", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    const ctrl = new WatchController({
      transport,
      getPendingPermissions: () => [
        { requestId: "r1", tool: "Bash", command: "ls", risk: "low" },
      ],
      keepaliveMs: 100000,
    });
    ctrl.start();
    ctrl.notifyPermissionsChanged();
    const approvals = transport.sent.filter((m) => m.type === "approval_request");
    assert.strictEqual(approvals.length, 1);
    assert.strictEqual(approvals[0].requestId, "r1");
    assert.strictEqual(approvals[0].tool, "Bash");
    ctrl.stop();
  });

  it("should not push permissions when list is empty", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    const ctrl = new WatchController({
      transport,
      getPendingPermissions: () => [],
      keepaliveMs: 100000,
    });
    ctrl.start();
    const before = transport.sent.length;
    ctrl.notifyPermissionsChanged();
    assert.strictEqual(transport.sent.length, before);
    ctrl.stop();
  });

  it("should stop interval on stop()", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    const ctrl = new WatchController({
      transport,
      getCurrentState: () => "idle",
      getCurrentSvg: () => null,
      getSessionSnapshot: () => ({ sessions: [] }),
      keepaliveMs: 50,
    });
    ctrl.start();
    ctrl.stop();
    const count = transport.sent.length;
    return new Promise((resolve) => {
      setTimeout(() => {
        assert.strictEqual(transport.sent.length, count);
        resolve();
      }, 150);
    });
  });

  it("should not push after stop even if notifyStateChanged is called", () => {
    const transport = createFakeTransport();
    transport.connected = true;
    const ctrl = new WatchController({
      transport,
      getCurrentState: () => "idle",
      getCurrentSvg: () => null,
      getSessionSnapshot: () => ({ sessions: [] }),
      keepaliveMs: 100000,
    });
    ctrl.start();
    ctrl.stop();
    const count = transport.sent.length;
    ctrl.notifyStateChanged();
    assert.strictEqual(transport.sent.length, count);
  });
});
