"use strict";

// Theme fingerprint — a short, deterministic hash identifying a pet theme by
// its id plus the exact set of SVG files it renders. The watch computes the
// same fingerprint from its locally-cached manifest; comparing the two lets
// either side detect when the watch is rendering a stale theme and needs a
// sync. The algorithm MUST stay byte-for-byte identical to the Kotlin
// implementation in ThemeConfig.kt (computeFingerprint):
//
//   input = themeId + ":" + sortedUniqueSvgFiles.join(",")
//   fingerprint = sha256(input)[0..5]   (first 6 lowercase hex chars)
//
// Filenames are ASCII, so JS UTF-16 sort and Kotlin natural String sort agree.

const crypto = require("crypto");

/**
 * Flatten a theme `states` map ({ state: [file, ...] }) into a sorted, unique
 * list of SVG filenames. Non-array / non-string entries are ignored.
 */
function collectSvgFiles(states) {
  const set = new Set();
  if (states && typeof states === "object") {
    for (const key of Object.keys(states)) {
      const arr = states[key];
      if (Array.isArray(arr)) {
        for (const file of arr) {
          if (typeof file === "string" && file) set.add(file);
        }
      }
    }
  }
  return Array.from(set).sort();
}

/**
 * Compute the 6-char theme fingerprint. Returns null when there is nothing to
 * fingerprint (no id and no files), so callers can omit the `th` field.
 */
function computeThemeFingerprint(themeId, states) {
  const id = String(themeId == null ? "" : themeId).trim();
  const files = collectSvgFiles(states);
  if (!id && files.length === 0) return null;
  const input = `${id}:${files.join(",")}`;
  return crypto.createHash("sha256").update(input, "utf8").digest("hex").slice(0, 6);
}

module.exports = { computeThemeFingerprint, collectSvgFiles };
