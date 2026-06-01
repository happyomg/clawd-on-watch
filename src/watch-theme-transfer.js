"use strict";

// Theme transfer framing — turns a theme bundle (manifest + raw SVG bytes) into
// a flat sequence of small JSON frames the desktop writes one-per-GATT-write to
// the watch's CWD5 characteristic. The watch reassembles them (see BleService
// CWD5 handling). Keeping the framing here (pure, testable) lets the Python
// bridge stay a dumb byte-pump.
//
// Frame shapes (compact keys to stay under the negotiated ATT MTU):
//   manifest: {t:"manifest", name, hash, stateMap, files:[...], totalBytes}
//   chunk:    {t:"chunk", f:<file>, i:<index>, c:<count>, d:<base64slice>}
//   done:     {t:"done", hash}

const DEFAULT_CHUNK_B64 = 420; // base64 chars/chunk — keeps total frame JSON < 512B (with ~60B overhead)

function base64Of(data) {
  if (Buffer.isBuffer(data)) return data.toString("base64");
  if (typeof data === "string") return Buffer.from(data, "utf8").toString("base64");
  return Buffer.from(data || []).toString("base64");
}

/**
 * Build the ordered frame list for a theme bundle.
 *
 * bundle = {
 *   name: string,                  // theme id (fingerprint input)
 *   hash: string,                  // 6-char fingerprint
 *   stateMap: { state: [file] },   // theme.states
 *   files: [file, ...],            // order to transfer (defaults to stateMap union)
 *   fileData: { file: Buffer|string }
 * }
 */
function buildThemeFrames(bundle, chunkB64 = DEFAULT_CHUNK_B64) {
  if (!bundle || typeof bundle !== "object") throw new Error("bundle required");
  const name = String(bundle.name || "");
  const hash = String(bundle.hash || "");
  const stateMap = bundle.stateMap && typeof bundle.stateMap === "object" ? bundle.stateMap : {};
  const fileData = bundle.fileData && typeof bundle.fileData === "object" ? bundle.fileData : {};
  const size = Math.max(1, Math.floor(chunkB64));

  const files = Array.isArray(bundle.files) && bundle.files.length
    ? bundle.files.slice()
    : Array.from(new Set(Object.values(stateMap).flat().filter((f) => typeof f === "string")));

  let totalBytes = 0;
  for (const f of files) {
    const d = fileData[f];
    if (Buffer.isBuffer(d)) totalBytes += d.length;
    else if (typeof d === "string") totalBytes += Buffer.byteLength(d, "utf8");
  }

  const frameMeta = bundle.frameMeta && typeof bundle.frameMeta === "object" ? bundle.frameMeta : null;
  // Keep manifest small (< 512B) — stateMap goes in the done frame so we
  // avoid Prepared Writes (which are unreliable on macOS CoreBluetooth).
  const manifest = { t: "manifest", name, hash, files, totalBytes };
  if (frameMeta) manifest.frameMeta = frameMeta;
  const frames = [manifest];

  for (const file of files) {
    const b64 = base64Of(fileData[file]);
    // ceil so an empty file still emits one (count=1) chunk with empty data,
    // letting the watch create the (empty) file deterministically.
    const count = Math.max(1, Math.ceil(b64.length / size));
    for (let i = 0; i < count; i++) {
      frames.push({
        t: "chunk",
        f: file,
        i,
        c: count,
        d: b64.slice(i * size, (i + 1) * size),
      });
    }
  }

  const done = { t: "done", hash };
  if (stateMap && Object.keys(stateMap).length > 0) done.stateMap = stateMap;
  frames.push(done);
  return frames;
}

module.exports = { buildThemeFrames, DEFAULT_CHUNK_B64 };
