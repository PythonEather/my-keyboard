#!/usr/bin/env node
// Plugin tests for CI (and for Termux: `node tools/plugin-test/run.mjs`).
//
// What it does for every plugin it finds:
//   1. loads the script in a Node mock of `kb` with the same prelude the app uses (the prelude is read from the app sources);
//   2. fires every event the plugin subscribed to (and the buttons, commands and panel renders it registered);
//   3. compiles every GLSL shader it handed to kb.overlay / kb.background / kb.setLayout-style items ({ type: "gl", frag, vert })
//      with glslangValidator, with the same changes the app makes (adds `precision`, default vertex shader),
//      and checks its `textures: { iTex0: "file.png" }`: the files exist in the plugin and every sampler2D has an entry;
//   4. checks that nothing throws, that the manifest is sane, and warns about slow calls.
//
// Where it looks: app/src/main/assets/plugins, app/src/main/assets/examples and ./plugins (your own plugins):
//   *.js with a /* @plugin {...} */ manifest comment, *.json with a "script" field, and folders with plugin.json + main.js.
// Usage: node tools/plugin-test/run.mjs [--require-glslang] [--verbose] [path ...]
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import os from "node:os";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..", "..");
const args = process.argv.slice(2);
const flags = new Set(args.filter((a) => a.startsWith("--")));
const targets = args.filter((a) => !a.startsWith("--"));
const verbose = flags.has("--verbose");

let failures = 0;
let warnings = 0;
const gha = !!process.env.GITHUB_ACTIONS;

function fail(file, msg) {
  failures++;
  console.log((gha ? `::error file=${file}::` : "  ✕ ") + msg);
}
function warn(file, msg) {
  warnings++;
  console.log((gha ? `::warning file=${file}::` : "  ! ") + msg);
}

// ---------------------------------------------------------------- the app's own prelude
const engineSrc = fs.readFileSync(path.join(root, "app/src/main/java/com/expressive/keyboard/ScriptEngine.kt"), "utf8");
const preludeMatch = engineSrc.match(/private val PRELUDE = """([\s\S]*?)""".trimIndent\(\)/);
if (!preludeMatch) {
  console.log("Could not find PRELUDE in ScriptEngine.kt");
  process.exit(2);
}
const mainPrelude = preludeMatch[1];

// Every kb.* function the app really defines, read from the app source: functions the mock does not implement become
// harmless no-ops, and a name that is NOT in this list (a typo, or an API from a newer version) stays undefined and throws.
const knownApi = (() => {
  const groups = { kb: "kb", globals: "" };
  for (const m of engineSrc.matchAll(/kb\["(\w+)"\]\s*=\s*(\w+)/g)) groups[m[2]] = m[1];
  const api = {};
  for (const m of engineSrc.matchAll(/def\((\w+),\s*"(\w+)"\)/g)) {
    const group = groups[m[1]];
    if (group === undefined) continue;
    (api[group] = api[group] || new Set()).add(m[2]);
  }
  return api;
})();
const extraPrelude = fs.readFileSync(path.join(root, "app/src/main/assets/kb/prelude-extra.js"), "utf8");

// ---------------------------------------------------------------- finding plugins
function findPlugins() {
  const dirs = targets.length ? targets.map((t) => path.resolve(t)) : [
    path.join(root, "app/src/main/assets/plugins"),
    path.join(root, "app/src/main/assets/examples"),
    path.join(root, "plugins"),
  ];
  const out = [];
  const visit = (p) => {
    if (!fs.existsSync(p)) return;
    const st = fs.statSync(p);
    if (st.isDirectory()) {
      if (fs.existsSync(path.join(p, "plugin.json")) || fs.existsSync(path.join(p, "manifest.json"))) { out.push({ kind: "dir", p }); return; }
      for (const f of fs.readdirSync(p).sort()) visit(path.join(p, f));
    } else if (/\.(js|json)$/.test(p)) out.push({ kind: "file", p });
  };
  dirs.forEach(visit);
  return out;
}

function stripBom(s) { return s.replace(/^\uFEFF/, ""); }

/** -> { manifest, script, files: { path: Buffer } } or null when the file is not a scripted plugin */
function loadPlugin(item) {
  if (item.kind === "dir") {
    const mf = fs.existsSync(path.join(item.p, "plugin.json")) ? "plugin.json" : "manifest.json";
    const manifest = JSON.parse(stripBom(fs.readFileSync(path.join(item.p, mf), "utf8")));
    const main = manifest.main || "main.js";
    const files = {};
    const walk = (d, rel) => {
      for (const f of fs.readdirSync(d)) {
        const full = path.join(d, f), r = rel ? rel + "/" + f : f;
        if (fs.statSync(full).isDirectory()) walk(full, r); else files[r] = fs.readFileSync(full);
      }
    };
    walk(item.p, "");
    const script = manifest.script || (files[main] ? files[main].toString("utf8") : null);
    for (const k of [mf, main]) delete files[k];
    if (!script) return null;
    return { manifest, script, files };
  }
  const raw = stripBom(fs.readFileSync(item.p, "utf8"));
  if (item.p.endsWith(".json")) {
    let manifest;
    try { manifest = JSON.parse(raw); } catch (e) { throw new Error("bad JSON: " + e.message); }
    if (typeof manifest.script !== "string") return null;
    const files = {};
    for (const [k, v] of Object.entries(manifest.files || {})) files[k] = Buffer.from(String(v), "base64");
    return { manifest, script: manifest.script, files };
  }
  const m = raw.match(/^\s*\/\*\s*@plugin([\s\S]*?)\*\//);
  if (!m) throw new Error("a .js mod must start with a comment like /* @plugin { \"id\": …, \"name\": … } */");
  return { manifest: JSON.parse(m[1].trim()), script: raw, files: {} };
}

// ---------------------------------------------------------------- the kb mock
const IMAGE = /\.(png|jpe?g|webp|gif)$/;

function makeKb(plugin, rec) {
  const handlers = {};
  const store = new Map();
  const saves = new Map();
  const perms = new Set(plugin.manifest.permissions || []);
  const need = (p, fn) => { if (!perms.has(p)) throw new Error(`kb.${fn} needs the "${p}" permission in the manifest`); };

  const kb = {
    pluginId: plugin.manifest.id || "test",
    apiVersion: 4,
    on(ev, fn) {
      if (typeof ev !== "string" || typeof fn !== "function") throw new Error("kb.on(event, function)");
      (handlers[ev] = handlers[ev] || []).push(fn);
    },
    off(ev) { delete handlers[ev]; },
    asset(p) {
      const buf = plugin.files[p];
      if (!buf) return null;
      if (IMAGE.test(p)) return `data:image/${p.split(".").pop()};base64,${buf.toString("base64")}`;
      return buf.toString("utf8");
    },
    storage: {
      get: (k) => (store.has(k) ? store.get(k) : null),
      set: (k, v) => { if (String(v).length >= 256 * 1024) throw new Error("storage: value is larger than 256 KB"); store.set(k, String(v)); },
      remove: (k) => { store.delete(k); },
      keys: () => [...store.keys()],
      clear: () => store.clear(),
    },
    saves: {
      rawGet: (k) => (saves.has(k) ? saves.get(k) : null),
      rawSet: (k, v) => {
        if (String(v).length >= 256 * 1024) throw new Error("saves: value is larger than 256 KB");
        if (!saves.has(k) && saves.size >= 100) throw new Error("saves: at most 100 slots and boards per plugin");
        saves.set(k, String(v));
      },
      rawRemove: (k) => { saves.delete(k); },
      rawKeys: () => [...saves.keys()],
    },
    config: () => ({ ...(rec.config || {}) }),
    setConfig: () => {}, setConfigs: () => {},
    overlay(items) { rec.items.push(["overlay", items]); },
    background(items) { rec.items.push(["background", items]); },
    clearOverlay() {}, clearBackground() {},
    defineGroup() {}, removeGroup() {},
    addButton(o, fn) { rec.buttons.push([o && o.id, fn]); },
    removeButton() {},
    addPanel(o, fn) { rec.panels.push([o && o.id, fn]); },
    addCommand(name, fn) { rec.commands.push([name, fn]); },
    refresh() {},
    glUniforms: () => true, glError: () => null, shaderError: () => null,
    supports: () => true,
    log: (...a) => rec.logs.push(["log", a.join(" ")]), warn: (...a) => rec.logs.push(["warn", a.join(" ")]),
    error: (...a) => rec.logs.push(["error", a.join(" ")]), debug: () => {}, logClear: () => {},
    toast() {}, status() {}, vibrate() {}, haptic() {},
    commit: () => {}, insert: () => {}, deleteBefore: () => {}, deleteAfter: () => {}, replaceBefore: () => {},
    textBefore: () => "", textAfter: () => "", selectedText: () => "", currentWord: () => "", cursor: () => [0, 0], allText: () => "",
    setSelection() {}, key() {}, enter() {}, sendKey() {}, setComposing() {}, finishComposing() {},
    language: () => "en", languages: () => ["en"], setLanguage: () => true, shift: () => "off", setShift() {},
    field: () => ({ type: "text", package: "test", password: false }),
    openPanel() {}, closePanel() {}, hide() {},
    keyboardSize: () => ({ width: 400, height: 260 }), keys: () => [], keyRect: () => null, pressKey() {},
    colors: () => ({ background: "#101010", key: "#202020", keyText: "#ffffff", accent: "#6750A4" }),
    device: () => ({ width: 400, height: 260, density: 2.75, dark: true, landscape: false }),
    setLayout() {}, setSkin() {}, setIcon() {}, resetIcon() {}, setSuggestions() {}, keyEffects() {}, keyboardEffect() {}, postEffect: () => false,
    clipboard: () => [], copy() {}, share() {},
    tone() {}, playAudio: () => 1, setAudio: () => true, stopAudio() {}, sound() {}, speak() {}, stopSpeaking() {},
    tilt: () => ({ x: 0, y: 0, z: 1 }),
    mods: () => [], broadcast() {},
    prompt() {}, closePrompt() {}, captureTouches() {}, screenOpen() {}, screenClose() {}, panZoom() {}, view() {}, toWorld: (x, y) => ({ x, y }), toScreen: (x, y) => ({ x, y }),
    openUrl() {}, launchApp() {}, appInfo: () => ({}), translate() {},
    particles: { set() {}, emit() {}, clear() {} },
    audio: { start() {}, stop() {} }, media: { info: () => ({}), play() {}, pause() {}, next() {}, previous() {} },
    settings: { get: () => null, set: () => false },
    dictionary: {}, emoji: {},
    files: {
      write() {}, append() {}, read: () => null, writeBase64() {}, readBase64: () => null, exists: () => false, delete: () => false, list: () => [],
    },
    util: {
      sha256: (s) => String(s), sha1: (s) => String(s), md5: (s) => String(s),
      base64Encode: (s) => Buffer.from(String(s)).toString("base64"), base64Decode: (s) => Buffer.from(String(s), "base64").toString(),
      urlEncode: encodeURIComponent, urlDecode: decodeURIComponent, uuid: () => "00000000-0000-4000-8000-000000000000",
      now: () => Date.now(), formatDate: () => "", transform: (n, t) => t,
    },
    http(o, cb) { need("network", "http"); rec.http.push(o && o.url); },
    ai(o, cb) { need("ai", "ai"); },
    setTimeout: undefined,
  };
  delete kb.setTimeout;
  for (const [group, names] of Object.entries(knownApi)) {
    if (group === "") continue;
    const target = group === "kb" ? kb : (kb[group] = kb[group] || {});
    for (const n of names) if (!(n in target)) target[n] = () => undefined;
  }
  return { kb, handlers };
}

// ---------------------------------------------------------------- GLSL validation
function haveGlslang() {
  const r = spawnSync("glslangValidator", ["--version"], { encoding: "utf8" });
  return !r.error && r.status === 0;
}

const VERT2 = "attribute vec2 aPos;\nvarying vec2 vUv;\nvoid main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
const VERT3 = "#version 300 es\nin vec2 aPos;\nout vec2 vUv;\nvoid main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

/** Same preparation the app does in GlRenderer.ensureProgram. */
function prepareShaders(item) {
  const frag = String(item.frag || "");
  const es3 = frag.trimStart().startsWith("#version 300 es");
  const fragSrc = !es3 && !frag.includes("precision") ? "precision mediump float;\n" + frag : frag;
  const vertSrc = item.vert && String(item.vert).trim() ? String(item.vert) : (es3 ? VERT3 : VERT2);
  return { fragSrc, vertSrc, es3 };
}

/** glslangValidator needs a #version line; GLSL ES 1.00 shaders in the app have none, so it is added for the check. */
function withVersion(src, es3) {
  if (/^\s*#version/.test(src)) return src;
  return "#version 100\n" + src;
}

function validateGl(file, label, item, glslang) {
  if (!item.frag || typeof item.frag !== "string") { fail(file, `${label}: gl item without a "frag" string`); return; }
  if (item.frag.length > 64000) fail(file, `${label}: shader is larger than 64000 characters (the app refuses it)`);
  const { fragSrc, vertSrc, es3 } = prepareShaders(item);
  if (!glslang) return;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "glsl-"));
  try {
    const f = path.join(dir, "s.frag"), v = path.join(dir, "s.vert");
    fs.writeFileSync(f, withVersion(fragSrc, es3));
    fs.writeFileSync(v, withVersion(vertSrc, es3));
    // both stages in one run: this also checks that the varyings match when the program is linked
    const r = spawnSync("glslangValidator", ["-l", v, f], { encoding: "utf8" });
    if (r.status !== 0) {
      const text = (r.stdout + r.stderr).split("\n").filter((l) => /ERROR|error/.test(l)).slice(0, 8).join("\n    ");
      fail(file, `${label}: shader does not compile:\n    ${text || (r.stdout + r.stderr).trim().slice(0, 600)}`);
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

const TEX_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,31}$/;
const TEX_RESERVED = new Set(["iPrev", "iTime", "iFrame", "iResolution", "iDensity", "iTouch", "iTilt", "iAudio"]);
const TEX_FORMATS = /\.(png|jpe?g|webp)$/i;

/** The same rules as GlTextures.parse in the app, plus: the files exist and the shader's sampler2D uniforms have an entry. */
function validateTextures(file, label, item, files) {
  const tex = item.textures;
  const declared = [...String(item.frag || "").matchAll(/uniform\s+(?:(?:lowp|mediump|highp)\s+)?sampler2D\s+(\w+)\s*;/g)].map((m) => m[1]).filter((n) => n !== "iPrev");
  if (tex === undefined || tex === null) {
    for (const n of declared) fail(file, `${label}: the shader reads sampler2D ${n} but the layer has no textures: { ${n}: "file.png" }`);
    return;
  }
  if (typeof tex !== "object" || Array.isArray(tex)) { fail(file, `${label}: textures must be an object like { iTex0: "brick.png" }`); return; }
  const names = Object.keys(tex);
  if (names.length > 4) warn(file, `${label}: only the first 4 textures are used (${names.length} given)`);
  for (const name of names.slice(0, 4)) {
    const v = tex[name];
    const where = `${label}: texture ${name}`;
    if (!TEX_NAME.test(name) || TEX_RESERVED.has(name)) { fail(file, `${where}: not a usable name (letters, digits and _, up to 32 characters, and not one of ${[...TEX_RESERVED].join(", ")})`); continue; }
    const src = typeof v === "string" ? v : v && typeof v === "object" ? v.src : undefined;
    // an empty string is an empty slot: an `image` setting nobody has filled in yet (the shader gets a 1x1 transparent pixel)
    if (typeof src === "string" && !src.trim()) continue;
    if (typeof src !== "string") { fail(file, `${where}: needs a file name of the .zip, a data: URI or the value of an image setting (a string, or { src: ... })`); continue; }
    if (v && typeof v === "object") {
      if (v.wrap !== undefined && !["clamp", "repeat", "mirror", "mirrored"].includes(String(v.wrap).toLowerCase())) warn(file, `${where}: wrap "${v.wrap}" is not known, clamp is used`);
      if (v.filter !== undefined && !["linear", "nearest"].includes(String(v.filter).toLowerCase())) warn(file, `${where}: filter "${v.filter}" is not known, linear is used`);
    }
    if (src.trim().startsWith("data:")) {
      if (!/^data:image\/[a-z0-9.+-]+;base64,/i.test(src.trim())) fail(file, `${where}: a data: URI must look like data:image/png;base64,...`);
      continue;
    }
    if (src.trim().startsWith("user:")) {
      // the value of an `image` setting (kb.config().photo): user:<setting id>:<stamp>
      if (!/^user:[A-Za-z0-9_.-]{1,40}:[^:]+$/.test(src.trim())) fail(file, `${where}: \"${src}\" is not the value of an image setting (user:<id>:<stamp>); pass kb.config().<id> as it is`);
      continue;
    }
    const clean = src.trim().replace(/\\/g, "/").replace(/^\.\//, "").replace(/^\/+/, "");
    if (clean.split("/").includes("..") || clean.length > 200) { fail(file, `${where}: "${src}" is not an allowed path`); continue; }
    const buf = files && files[clean];
    if (!buf) { fail(file, `${where}: no file "${clean}" in the plugin (a .zip mod must contain it)`); continue; }
    if (!TEX_FORMATS.test(clean)) warn(file, `${where}: "${clean}" is not a PNG, JPEG or WebP file, the phone may not be able to read it`);
    if (buf.length > 16 * 1024 * 1024) fail(file, `${where}: "${clean}" is larger than 16 MB`);
  }
  const given = new Set(names);
  for (const n of declared) if (!given.has(n)) fail(file, `${label}: the shader reads sampler2D ${n} but textures has no ${n}`);
  for (const n of names.slice(0, 4)) if (!declared.includes(n) && TEX_NAME.test(n) && !TEX_RESERVED.has(n)) warn(file, `${label}: texture ${n} is not read by the shader (no uniform sampler2D ${n})`);
}

function collectGl(items, out, depth = 0) {
  if (!Array.isArray(items) || depth > 8) return;
  for (const it of items) {
    if (!it || typeof it !== "object") continue;
    if (it.type === "gl") out.push(it);
    if (Array.isArray(it.items)) collectGl(it.items, out, depth + 1);
    if (Array.isArray(it.children)) collectGl(it.children, out, depth + 1);
  }
}

// ---------------------------------------------------------------- events
const SAMPLE = {
  key: { text: "a" }, word: { word: "hello", previous: "say" }, suggest: { word: "hel", previous: "" },
  start: { type: "text", package: "test", password: false }, press: { label: "a", type: "character" },
  typing: { kind: "letter", cps: 4, backspaceCps: 0, burst: 5, gapMs: 120 },
  selection: { start: 0, end: 0 }, pick: { word: "hello" }, autocorrect: { word: "teh", correction: "the" },
  voice: { text: "hello" }, glide: { word: "hello", alternatives: [] }, panel: "keyboard", shift: "off", language: "en",
  device: { width: 400, height: 260, density: 2.75, dark: true, landscape: false },
  clipboard: "copied", touch: { type: "down", x: 10, y: 10, wx: 10, wy: 10, pointer: 0 }, view: { x: 0, y: 0, zoom: 1 },
  message: { from: "other", name: "x", data: null }, option: { id: "x", value: 1 },
  pointer: { type: "move", x: 50, y: 50, pointer: 0 }, tilt: { x: 0.1, y: 0.2, z: 0.9 }, gyro: { x: 0, y: 0, z: 0 },
  shake: { strength: 3.1 }, audio: { level: 0.3, bands: [0, 0, 0, 0, 0, 0, 0, 0] },
  app: { package: "test", name: "Test", color: "#336699" },
  media: { access: true, active: true, playing: true, title: "t", artist: "a", album: "b", app: "p", position: 1, duration: 100 },
  frame: 16, space: undefined, enter: undefined, backspace: undefined, show: undefined, hide: undefined, finish: undefined,
};

function timed(file, label, budget, fn, state) {
  const t0 = process.hrtime.bigint();
  try {
    const r = fn();
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    // a desktop is 5-10x faster than a phone: warn at a tenth of the budget
    if (ms > budget / 10) warn(file, `${label} took ${ms.toFixed(1)} ms here — a phone is several times slower (limit ${budget} ms)`);
    return r;
  } catch (e) {
    state.errors++;
    fail(file, `${label} threw: ${e && e.stack ? e.stack.split("\n").slice(0, 3).join(" | ") : e}`);
    return undefined;
  }
}

function runPlugin(item, glslang) {
  const file = path.relative(root, item.p);
  let plugin;
  try {
    plugin = loadPlugin(item);
  } catch (e) {
    fail(file, "cannot read: " + e.message);
    return;
  }
  if (!plugin) { if (verbose) console.log(`- ${file}: no script, skipped`); return; }
  console.log(`• ${file}`);
  const m = plugin.manifest;
  // the plugin runs once with the manifest defaults and once with every toggle on, so code behind settings is reached too
  const defaults = {}, allOn = {};
  for (const c of m.config || []) {
    if (!c || !c.id) continue;
    if ("default" in c) { defaults[c.id] = c.default; allOn[c.id] = c.default; }
    if (c.type === "toggle") allOn[c.id] = true;
    // an image setting is empty by default; the second run has a picture chosen (the app gives the plugin "user:<id>:<stamp>")
    if (c.type === "image") { defaults[c.id] = ""; allOn[c.id] = `user:${c.id}:1`; }
  }
  const variants = [["defaults", defaults]];
  if (JSON.stringify(allOn) !== JSON.stringify(defaults)) variants.push(["all toggles on, pictures chosen", allOn]);
  checkManifest(file, m);
  const shaderSeen = new Set();
  for (const [name, cfg] of variants) {
    if (variants.length > 1) console.log(`  — ${name}`);
    runVariant(file, plugin, glslang, cfg, shaderSeen);
  }
}

function checkManifest(file, m) {
  if (!m.id || typeof m.id !== "string") fail(file, 'manifest has no "id"');
  if (!m.name) warn(file, 'manifest has no "name"');
  for (const p of m.permissions || []) {
    if (!["network", "read_text", "clipboard", "launch", "ai", "sensors", "microphone", "app_info", "screen", "media", "settings_layout", "toolbar_layout"].includes(p)) {
      fail(file, `unknown permission "${p}"`);
    }
  }
}

const texSeen = new Set();

function runVariant(file, plugin, glslang, cfg, shaderSeen) {
  const m = plugin.manifest;
  const budget = Math.min(2000, Math.max(300, m.callBudgetMs || 300));
  const rec = { items: [], buttons: [], panels: [], commands: [], logs: [], http: [], config: cfg };
  const { kb, handlers } = makeKb(plugin, rec);
  const timers = [];
  const sandbox = {
    kb, console: { log: (...a) => rec.logs.push(["log", a.join(" ")]) },
    setTimeout: (fn, ms) => { timers.push(fn); return timers.length; }, clearTimeout() {}, setInterval: (fn) => { timers.push(fn); return timers.length; }, clearInterval() {},
    Promise, JSON, Math, Date, parseInt, parseFloat, isNaN, isFinite, Array, Object, String, Number, Boolean, RegExp, Error, TypeError, RangeError,
    Map, Set, WeakMap, Symbol, Uint8Array, Float32Array, Float64Array, Int32Array, Uint16Array, Uint32Array, ArrayBuffer, DataView,
    btoa: (s) => Buffer.from(String(s), "latin1").toString("base64"), atob: (s) => Buffer.from(String(s), "base64").toString("latin1"),
  };
  const ctx = vm.createContext(sandbox);
  const state = { errors: 0 };

  // prelude, then the plugin: same order as the app
  for (const [name, src] of [["kb-prelude.js", mainPrelude], ["prelude-extra.js", extraPrelude]]) {
    try { vm.runInContext(src, ctx, { filename: name }); } catch (e) { fail(file, `${name} failed in the mock: ${e.message}`); return; }
  }
  timed(file, "load", 2000, () => vm.runInContext(plugin.script, ctx, { filename: m.id + ".js", timeout: 2000 }), state);
  if (state.errors) return;

  const collect = () => {
    for (const [kind, items] of rec.items) {
      const gl = [];
      collectGl(items, gl);
      if (gl.length > 4) warn(file, `${kind}: ${gl.length} GL layers in one list, the app draws only the first 4`);
      gl.forEach((g, i) => (rec.gl = rec.gl || []).push([kind, i, g]));
    }
    rec.items.length = 0;
  };
  collect();

  // events
  const names = Object.keys(handlers);
  for (const ev of names) {
    if (!(ev in SAMPLE)) warn(file, `kb.on("${ev}") — the app has no event with this name (check the spelling)`);
    const arg = SAMPLE[ev];
    for (const fn of handlers[ev]) {
      const rounds = ev === "frame" ? 5 : 1;
      for (let i = 0; i < rounds; i++) timed(file, `event "${ev}"`, budget, () => fn(ev === "frame" ? 16 : arg), state);
    }
  }
  // buttons, commands, panels, timers
  for (const [id, fn] of rec.buttons) if (typeof fn === "function") timed(file, `button "${id}"`, budget, () => fn(), state);
  for (const [name, fn] of rec.commands) if (typeof fn === "function") timed(file, `command "${name}"`, budget, () => fn(), state);
  for (const [id, fn] of rec.panels) {
    if (typeof fn !== "function") continue;
    const nodes = timed(file, `panel "${id}" render`, budget, () => fn(), state);
    if (nodes !== undefined && nodes !== null && !Array.isArray(nodes) && typeof nodes !== "object") warn(file, `panel "${id}" render() should return an array of elements`);
  }
  for (const fn of timers.splice(0, 8)) if (typeof fn === "function") timed(file, "timer", budget, () => fn(), state);
  collect();

  // shaders (deduplicated by source, across variants)
  const seen = shaderSeen;
  for (const [kind, i, g] of rec.gl || []) {
    const key = g.frag + "\u0000" + (g.vert || "");
    // textures are checked for every layer (the same shader may be given different pictures); once per distinct layer
    if (typeof g.frag === "string") {
      const tkey = key + "\u0000" + JSON.stringify(g.textures === undefined ? null : g.textures).slice(0, 400);
      if (!texSeen.has(file + tkey)) {
        texSeen.add(file + tkey);
        validateTextures(file, `${kind} gl layer #${i}${g.id ? ` "${g.id}"` : ""}`, g, plugin.files);
      }
    }
    if (seen.has(key)) continue;
    seen.add(key);
    validateGl(file, `${kind} gl layer #${i}${g.id ? ` "${g.id}"` : ""}`, g, glslang);
  }
  if (!state.errors) console.log(`  ✓ ${names.length} event type(s), ${rec.buttons.length} button(s), ${rec.panels.length} panel(s), ${seen.size} shader(s)`);
  for (const [lvl, text] of rec.logs) if (lvl === "error") warn(file, "kb.error during the run: " + text);
}

// ---------------------------------------------------------------- built-in self test of kb.require / kb.saves
function selfTest(glslang) {
  console.log("• prelude self-test (kb.require, kb.saves)");
  const files = {
    "lib/math.js": Buffer.from("exports.twice = function (x) { return x * 2; }; exports.name = 'math';"),
    "lib/maze.js": Buffer.from("var m = require('./math'); module.exports = { grid: function (n) { return m.twice(n); }, up: require('../top') };"),
    "top.js": Buffer.from("module.exports = 'top';"),
    "data/level.json": Buffer.from('{"w":3}'),
    "cyc/a.js": Buffer.from("exports.a1 = 1; var b = require('./b'); exports.a2 = b.seen;"),
    "cyc/b.js": Buffer.from("var a = require('./a'); exports.seen = a.a1;"),
    "bad.js": Buffer.from("throw new Error('boom');"),
  };
  const rec = { items: [], buttons: [], panels: [], commands: [], logs: [], http: [] };
  const { kb } = makeKb({ manifest: { id: "selftest", permissions: [] }, files }, rec);
  const ctx = vm.createContext({ kb, JSON, Date, Math, Object, Array, String, Number, Error, Promise, RegExp, Function });
  vm.runInContext(mainPrelude, ctx);
  vm.runInContext(extraPrelude, ctx);
  const t = (name, code, expect) => {
    try {
      const got = vm.runInContext(code, ctx);
      if (JSON.stringify(got) !== JSON.stringify(expect)) fail("prelude-extra.js", `${name}: got ${JSON.stringify(got)}, expected ${JSON.stringify(expect)}`);
    } catch (e) { fail("prelude-extra.js", `${name} threw: ${e.message}`); }
  };
  const throws = (name, code, part) => {
    try { vm.runInContext(code, ctx); fail("prelude-extra.js", `${name}: should have thrown`); }
    catch (e) { if (!String(e.message).includes(part)) fail("prelude-extra.js", `${name}: wrong message "${e.message}"`); }
  };
  t("require relative", "kb.require('lib/maze.js').grid(4)", 8);
  t("require parent dir", "kb.require('lib/maze').up", "top");
  t("require cached", "kb.require('lib/math') === kb.require('./lib/math.js')", true);
  t("require json", "kb.require('data/level.json').w", 3);
  t("require cycle", "kb.require('cyc/a').a2", 1);
  throws("require missing", "kb.require('nope')", 'no file "nope"');
  throws("require leaves folder", "kb.require('../x')", "leaves the plugin folder");
  throws("require error names file", "kb.require('bad')", "boom");
  t("saves roundtrip", "kb.saves.save('a', {lvl: 3}, 'L3'); kb.saves.load('a')", { lvl: 3 });
  t("saves fallback", "kb.saves.load('missing', 'fb')", "fb");
  t("saves list", "kb.saves.list().map(function (s) { return s.slot + ':' + s.title; })", ["a:L3"]);
  throws("saves bad name", "kb.saves.save('../evil', 1)", "bad slot name");
  t("highscore place", "kb.saves.highScore('m', 100, {name:'a'}) + ',' + kb.saves.highScore('m', 300, {name:'b'}) + ',' + kb.saves.highScore('m', 200)", "1,1,2");
  t("highscore order", "kb.saves.highScores('m').map(function (e) { return e.score; })", [300, 200, 100]);
  t("highscore lower is better", "kb.saves.highScore('t', 50, {lower:true}); kb.saves.highScore('t', 20, {lower:true}); kb.saves.highScores('t').map(function (e) { return e.score; })", [20, 50]);
  t("highscore list is capped", "var r = 0; [3, 4, 5, 0].forEach(function (v) { r = kb.saves.highScore('cap', v, {max: 3}); }); r", 0);
  t("export has everything", "var o = JSON.parse(kb.saves.exportText()); Object.keys(o.slots).join() + '|' + Object.keys(o.scores).sort().join()", "a|cap,m,t");
  // import into a fresh "install"
  const exported = vm.runInContext("kb.saves.exportText()", ctx);
  const rec2 = { items: [], buttons: [], panels: [], commands: [], logs: [], http: [] };
  const second = makeKb({ manifest: { id: "selftest", permissions: [] }, files }, rec2);
  const ctx2 = vm.createContext({ kb: second.kb, JSON, Date, Math, Object, Array, String, Number, Error, Promise, RegExp, Function });
  vm.runInContext(mainPrelude, ctx2); vm.runInContext(extraPrelude, ctx2);
  ctx2.__text = exported;
  const res = vm.runInContext("kb.saves.importText(__text)", ctx2);
  if (res.slots !== 1 || res.boards < 3) fail("prelude-extra.js", "import result " + JSON.stringify(res));
  const again = vm.runInContext("kb.saves.importText(__text)", ctx2);
  if (again.slots !== 0) fail("prelude-extra.js", "re-import of the same save should skip it, got " + JSON.stringify(again));
  const merged = vm.runInContext("kb.saves.highScores('m').length", ctx2);
  if (merged !== 3) fail("prelude-extra.js", "high scores should not duplicate on re-import, got " + merged);
  ctx2.__bad = "not json";
  try { vm.runInContext("kb.saves.importText(__bad)", ctx2); fail("prelude-extra.js", "bad import text should throw"); } catch (e) { /* expected */ }
  if (!failures) console.log("  ✓ ok");
  void glslang;
}

// ---------------------------------------------------------------- main
const glslang = haveGlslang();
if (!glslang) {
  if (flags.has("--require-glslang")) { console.log("glslangValidator is required but not installed"); process.exit(2); }
  console.log("(glslangValidator not found: shaders are not compiled. Install it: sudo apt install glslang-tools)");
}
selfTest(glslang);
const found = findPlugins();
let count = 0;
for (const item of found) {
  try { runPlugin(item, glslang); count++; } catch (e) { fail(path.relative(root, item.p), "test runner crashed: " + (e && e.stack || e)); }
}
console.log(`\n${count} file(s) checked, ${failures} failure(s), ${warnings} warning(s)`);
process.exit(failures ? 1 : 0);
