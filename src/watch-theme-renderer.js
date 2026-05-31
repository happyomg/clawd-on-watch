"use strict";

// Offscreen SVG → frame-sequence renderer for the Watch theme sync pipeline.
// Self-contained within the watch module — the only external dependency is
// Electron's BrowserWindow constructor, passed in by the caller.
//
// For each SVG file in a theme, this module:
//   1. Loads the SVG in an invisible offscreen BrowserWindow
//   2. Measures the CSS animation loop duration via JS
//   3. Captures one frame per interval via webContents.capturePage()
//   4. Returns PNG Buffers + meta (loopMs, count, fps)
//
// The result is passed to watch-theme-transfer.js which chunks and pushes it
// to the Watch over CWD5 — the Watch never needs a WebView.

const path = require("path");
const fs = require("fs");

const FRAME_SIZE = 192;
const FPS = 20;
const MIN_FRAMES = 12;
const MAX_FRAMES = 100;
const DEFAULT_LOOP_MS = 2000;
const RENDER_SETTLE_MS = 400;
const SVG_LOAD_TIMEOUT_MS = 5000;

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function buildHarnessHtml(svgFileUrl) {
  return `<!DOCTYPE html><html><head><meta charset="utf-8">
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:100%;height:100%;background:transparent;overflow:hidden}
#c{width:100%;height:100%;position:relative;overflow:hidden}
#pet{width:160%;height:160%;position:absolute;left:-30%;top:-65%}
</style></head>
<body><div id="c">
  <object id="pet" type="image/svg+xml" data="${svgFileUrl}"></object>
</div>
<script>
function loopMs(){
  try {
    var svg = document.getElementById('pet');
    var doc = svg.contentDocument;
    if (!doc) return 0;
    var nodes = doc.querySelectorAll('*');
    var max = 0;
    for (var i = 0; i < nodes.length; i++) {
      var s = getComputedStyle(nodes[i]);
      var dur = parseSec(s.animationDuration);
      var del = parseSec(s.animationDelay);
      var d = dur + del;
      var it = s.animationIterationCount;
      if (it && it !== 'infinite') {
        var n = parseFloat(it);
        if (!isNaN(n)) d = dur * n + del;
      }
      if (d > max) max = d;
    }
    return Math.round(max * 1000);
  } catch(e) { return 0; }
}
function parseSec(v) {
  if (!v) return 0;
  var t = 0;
  v.split(',').forEach(function(p) {
    p = p.trim();
    var m = p.match(/([0-9.]+)(ms|s)?/);
    if (m) { var n = parseFloat(m[1]); if (m[2] === 'ms') n /= 1000; if (n > t) t = n; }
  });
  return t;
}
</script></body></html>`;
}

/**
 * Render all SVG files in a theme to frame sequences.
 *
 * @param {Function} BrowserWindow — Electron BrowserWindow constructor
 * @param {string[]} svgFiles — list of SVG filenames
 * @param {Function} getAssetPath — (filename) => absolute file path
 * @param {Function} [log] — optional logger
 * @returns {Promise<Object>} — { [svgBaseName]: { frames: [Buffer], meta: {loopMs,count,fps} } }
 */
async function renderThemeFrames(BrowserWindow, svgFiles, getAssetPath, log) {
  if (!log) log = () => {};
  const result = {};

  let win = null;
  try {
    win = new BrowserWindow({
      width: FRAME_SIZE,
      height: FRAME_SIZE,
      show: false,
      frame: false,
      transparent: true,
      resizable: false,
      skipTaskbar: true,
      webPreferences: {
        offscreen: true,
        backgroundThrottling: false,
        nodeIntegration: false,
        contextIsolation: true,
      },
    });

    for (const svgFile of svgFiles) {
      const baseName = svgFile.replace(/\.svg$/i, "");
      try {
        const assetPath = getAssetPath(svgFile);
        if (!assetPath || !fs.existsSync(assetPath)) {
          log(`skip ${svgFile}: asset not found`);
          continue;
        }
        const fileUrl = `file://${assetPath.replace(/ /g, "%20")}`;
        const html = buildHarnessHtml(fileUrl);

        await win.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
        await delay(RENDER_SETTLE_MS);

        // Wait for SVG <object> to load
        const loaded = await Promise.race([
          win.webContents.executeJavaScript(`
            new Promise(function(resolve) {
              var pet = document.getElementById('pet');
              if (pet.contentDocument && pet.contentDocument.querySelector('svg')) { resolve(true); return; }
              pet.onload = function() { resolve(true); };
              setTimeout(function() { resolve(false); }, ${SVG_LOAD_TIMEOUT_MS - RENDER_SETTLE_MS});
            })
          `),
          delay(SVG_LOAD_TIMEOUT_MS).then(() => false),
        ]);

        if (!loaded) {
          log(`skip ${svgFile}: load timeout`);
          continue;
        }

        await delay(200); // let first animation frame settle

        const loopMs = await win.webContents.executeJavaScript("loopMs()").catch(() => 0);
        const effectiveLoop = loopMs > 0 ? loopMs : DEFAULT_LOOP_MS;
        const frameIntervalMs = Math.max(1, Math.floor(1000 / FPS));
        const frameCount = Math.min(MAX_FRAMES, Math.max(MIN_FRAMES, Math.floor(effectiveLoop / frameIntervalMs)));
        const coveredLoopMs = frameCount * frameIntervalMs;

        const frames = [];
        const captureRect = { x: 0, y: 0, width: FRAME_SIZE, height: FRAME_SIZE };

        for (let i = 0; i < frameCount; i++) {
          const image = await win.webContents.capturePage(captureRect);
          if (image && typeof image.toPNG === "function") {
            frames.push(image.toPNG());
          }
          if (i < frameCount - 1) await delay(frameIntervalMs);
        }

        if (frames.length > 0) {
          result[baseName] = {
            frames,
            meta: { loopMs: coveredLoopMs, count: frames.length, fps: FPS },
          };
          log(`rendered ${svgFile}: ${frames.length} frames, loop=${coveredLoopMs}ms`);
        }
      } catch (err) {
        log(`error rendering ${svgFile}: ${err.message || err}`);
      }
    }
  } finally {
    if (win && !win.isDestroyed()) win.destroy();
  }

  return result;
}

module.exports = { renderThemeFrames, FRAME_SIZE, FPS };
