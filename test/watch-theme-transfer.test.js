"use strict";

const { describe, it } = require("node:test");
const assert = require("node:assert");
const { buildThemeFrames } = require("../src/watch-theme-transfer");

function reassemble(frames) {
  // Mirror of the watch-side reassembly: collect chunks per file by index.
  const manifest = frames.find((f) => f.t === "manifest");
  const done = frames.find((f) => f.t === "done");
  const parts = {};
  for (const f of frames) {
    if (f.t !== "chunk") continue;
    (parts[f.f] ||= [])[f.i] = f.d;
  }
  const files = {};
  for (const [name, slices] of Object.entries(parts)) {
    files[name] = Buffer.from(slices.join(""), "base64");
  }
  return { manifest, done, files };
}

describe("watch theme transfer framing", () => {
  const bundle = {
    name: "calico",
    hash: "b7e2d4",
    stateMap: { idle: ["calico-idle.svg"], working: ["calico-typing.svg"] },
    files: ["calico-idle.svg", "calico-typing.svg"],
    fileData: {
      "calico-idle.svg": Buffer.from("<svg>idle</svg>".repeat(40), "utf8"),
      "calico-typing.svg": Buffer.from("<svg>typing</svg>", "utf8"),
    },
  };

  it("starts with a manifest and ends with done", () => {
    const frames = buildThemeFrames(bundle);
    assert.strictEqual(frames[0].t, "manifest");
    assert.strictEqual(frames[frames.length - 1].t, "done");
    assert.strictEqual(frames[0].name, "calico");
    assert.strictEqual(frames[frames.length - 1].hash, "b7e2d4");
    assert.deepStrictEqual(frames[0].files, ["calico-idle.svg", "calico-typing.svg"]);
  });

  it("chunks respect the byte budget and carry a stable count", () => {
    const frames = buildThemeFrames(bundle, 16);
    const idleChunks = frames.filter((f) => f.t === "chunk" && f.f === "calico-idle.svg");
    assert.ok(idleChunks.length > 1, "large file should split into multiple chunks");
    for (const ch of idleChunks) {
      assert.ok(ch.d.length <= 16);
      assert.strictEqual(ch.c, idleChunks.length);
    }
    // Indices are contiguous 0..c-1.
    idleChunks.forEach((ch, i) => assert.strictEqual(ch.i, i));
  });

  it("round-trips file bytes exactly through reassembly", () => {
    const frames = buildThemeFrames(bundle, 16);
    const { files, manifest, done } = reassemble(frames);
    assert.ok(files["calico-idle.svg"].equals(bundle.fileData["calico-idle.svg"]));
    assert.ok(files["calico-typing.svg"].equals(bundle.fileData["calico-typing.svg"]));
    assert.strictEqual(done.hash, manifest.hash);
  });

  it("totalBytes equals the sum of raw file sizes", () => {
    const frames = buildThemeFrames(bundle);
    const expected = bundle.fileData["calico-idle.svg"].length + bundle.fileData["calico-typing.svg"].length;
    assert.strictEqual(frames[0].totalBytes, expected);
  });

  it("emits one empty chunk for an empty file", () => {
    const frames = buildThemeFrames({
      name: "t", hash: "x", stateMap: { idle: ["e.svg"] },
      files: ["e.svg"], fileData: { "e.svg": Buffer.alloc(0) },
    });
    const chunks = frames.filter((f) => f.t === "chunk" && f.f === "e.svg");
    assert.strictEqual(chunks.length, 1);
    assert.strictEqual(chunks[0].c, 1);
    assert.strictEqual(chunks[0].d, "");
  });

  it("derives files from stateMap when files[] is omitted", () => {
    const frames = buildThemeFrames({
      name: "t", hash: "x",
      stateMap: { idle: ["a.svg"], working: ["a.svg", "b.svg"] },
      fileData: { "a.svg": "A", "b.svg": "B" },
    });
    assert.deepStrictEqual(new Set(frames[0].files), new Set(["a.svg", "b.svg"]));
  });

  it("throws without a bundle", () => {
    assert.throws(() => buildThemeFrames(null));
  });
});
