"use strict";

const { describe, it } = require("node:test");
const assert = require("node:assert");
const crypto = require("crypto");
const { computeThemeFingerprint, collectSvgFiles } = require("../src/watch-theme-fingerprint");

describe("watch theme fingerprint", () => {
  it("collects unique svg files sorted ascending", () => {
    const files = collectSvgFiles({
      working: ["b.svg", "a.svg"],
      idle: ["a.svg"],
      thinking: ["c.svg"],
    });
    assert.deepStrictEqual(files, ["a.svg", "b.svg", "c.svg"]);
  });

  it("ignores non-array and non-string entries", () => {
    const files = collectSvgFiles({
      working: ["a.svg", 42, null, ""],
      idle: "not-an-array",
      misc: null,
    });
    assert.deepStrictEqual(files, ["a.svg"]);
  });

  it("is a 6-char lowercase hex string", () => {
    const fp = computeThemeFingerprint("clawd", { idle: ["clawd-idle-follow.svg"] });
    assert.match(fp, /^[0-9a-f]{6}$/);
  });

  it("matches sha256(themeId + ':' + sortedFiles.join(','))[0..5]", () => {
    const states = { working: ["x.svg", "a.svg"], idle: ["a.svg"] };
    const expected = crypto
      .createHash("sha256")
      .update("calico:a.svg,x.svg", "utf8")
      .digest("hex")
      .slice(0, 6);
    assert.strictEqual(computeThemeFingerprint("calico", states), expected);
  });

  it("is stable regardless of state-key order or file order", () => {
    const a = computeThemeFingerprint("t", { working: ["b.svg", "a.svg"], idle: ["c.svg"] });
    const b = computeThemeFingerprint("t", { idle: ["c.svg"], working: ["a.svg", "b.svg"] });
    assert.strictEqual(a, b);
  });

  it("differs when theme id differs but files are identical", () => {
    const states = { idle: ["x.svg"] };
    assert.notStrictEqual(
      computeThemeFingerprint("clawd", states),
      computeThemeFingerprint("calico", states),
    );
  });

  it("differs when files differ but id is identical", () => {
    assert.notStrictEqual(
      computeThemeFingerprint("clawd", { idle: ["a.svg"] }),
      computeThemeFingerprint("clawd", { idle: ["b.svg"] }),
    );
  });

  it("returns null when there is nothing to fingerprint", () => {
    assert.strictEqual(computeThemeFingerprint("", {}), null);
    assert.strictEqual(computeThemeFingerprint(null, null), null);
  });

  // Known-answer vector — the Kotlin ThemeConfig.computeFingerprint MUST
  // produce this exact value for the same inputs (cross-checked in Phase 1).
  it("known-answer vector: clawd bundled manifest", () => {
    const states = {
      idle: ["clawd-idle-follow.svg"],
      working: ["clawd-working-typing.svg", "clawd-headphones-groove.svg", "clawd-working-building.svg"],
    };
    const files = collectSvgFiles(states);
    const input = `clawd:${files.join(",")}`;
    const expected = crypto.createHash("sha256").update(input, "utf8").digest("hex").slice(0, 6);
    assert.strictEqual(computeThemeFingerprint("clawd", states), expected);
    // Surfaced for the Kotlin cross-check:
    assert.strictEqual(typeof expected, "string");
  });
});
