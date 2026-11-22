/* @plugin
{
  "id": "gl.neon3d",
  "name": "GLSL: Neon strip + 3D cube",
  "version": "1.0",
  "description": "Test for real GLSL (OpenGL ES, Android 8+): neon RGB strip under the keys and a spinning 3D cube",
  "icon": "🧊",
  "scriptEngine": "quickjs",
  "config": [
    { "id": "strip", "title": "Neon strip", "type": "toggle", "default": true },
    { "id": "rgb", "title": "Cycle colours (RGB)", "type": "toggle", "default": true, "showIf": "strip" },
    { "id": "color", "title": "Strip colour (when RGB is off)", "type": "color", "default": "#00E5FF", "showIf": "strip" },
    { "id": "height", "title": "Glow height (dp)", "type": "slider", "min": 4, "max": 40, "step": 1, "default": 14, "showIf": "strip" },
    { "id": "cube", "title": "3D cube", "type": "toggle", "default": true }
  ]
}
*/

// ---------------------------------------------------------------------------
// GL item (kb.overlay / kb.background):
//   { type: "gl", frag, vert?, uniforms?, mesh?, x, y, w, h, scale, fps, depth, clear, alpha, rotation }
//   - frag: GLSL ES 1.00, or GLSL ES 3.00 if it starts with "#version 300 es"
//   - no w/h: covers the whole drawing area; x, y, w, h are dp
//   - scale 0.1..1: render resolution (lower = faster), fps 1..60 (default 30)
//   - depth: true turns on the depth buffer; clear: background colour (default transparent)
//   - output PREMULTIPLIED alpha: gl_FragColor = vec4(rgb * a, a)
//   - built-in uniforms (declare to receive): float iTime, float iFrame, vec3 iResolution (px),
//     float iDensity, vec3 iTouch (x, y px from top-left, pressed 0/1), vec3 iTilt, float iAudio
//   - your own uniforms: number, array of numbers (vec2..vec4, mat2..mat4) or "#RRGGBB" (vec4)
//   - mesh: { key, mode: "triangles"|"strip"|"fan"|"lines"|"lineStrip"|"points",
//             attribs: { aPos: { size: 3, data: [...] }, ... }, indices: [...] }
//     Without a mesh a fullscreen quad with attribute vec2 aPos is used.
//     Send the mesh once; to reuse it afterwards send { key } only.
//   - gl_FragCoord origin is the BOTTOM-left; iTouch origin is the TOP-left
// kb.glError(frag) returns the last compile/link error. Errors also go to the mod log.
// ---------------------------------------------------------------------------

var STRIP = [
  "precision mediump float;",
  "uniform float iTime;",
  "uniform vec3 iResolution;",
  "uniform float iDensity;",
  "uniform vec4 uColor;",
  "uniform float uRgb;",
  "uniform float uHeight;",
  "vec3 hue(float h) {",
  "  return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);",
  "}",
  "void main() {",
  "  float dp = gl_FragCoord.y / iDensity;",          // dp above the bottom edge
  "  float x = gl_FragCoord.x / iResolution.x;",
  "  vec3 c = uRgb > 0.5 ? hue(fract(x * 0.8 - iTime * 0.12)) : uColor.rgb;",
  "  float core = smoothstep(2.5, 0.0, abs(dp - 3.0));",
  "  float glow = exp(-max(dp - 3.0, 0.0) / (uHeight * 0.35));",
  "  glow *= 1.0 - smoothstep(uHeight, uHeight * 2.0, dp);",
  "  float pulse = 0.85 + 0.15 * sin(iTime * 2.0 + x * 6.0);",
  "  float a = clamp(core + glow * 0.75 * pulse, 0.0, 1.0);",
  "  vec3 col = mix(c, vec3(1.0), core * 0.6);",
  "  gl_FragColor = vec4(col * a, a);",
  "}"
].join("\n");

var CUBE_V = [
  "attribute vec3 aPos;",
  "attribute vec3 aCol;",
  "uniform float iTime;",
  "varying vec3 vCol;",
  "varying float vShade;",
  "void main() {",
  "  float a = iTime * 0.9;",
  "  float b = iTime * 0.6;",
  "  mat3 ry = mat3(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a));",
  "  mat3 rx = mat3(1.0, 0.0, 0.0, 0.0, cos(b), sin(b), 0.0, -sin(b), cos(b));",
  "  vec3 p = rx * ry * aPos;",
  "  vCol = aCol;",
  "  vShade = 0.6 - p.z * 0.6;",
  "  gl_Position = vec4(p.x * 2.6, p.y * 2.6, p.z * 0.5, p.z + 3.2);",
  "}"
].join("\n");

var CUBE_F = [
  "precision mediump float;",
  "varying vec3 vCol;",
  "varying float vShade;",
  "void main() {",
  "  gl_FragColor = vec4(vCol * clamp(vShade, 0.25, 1.0), 1.0);",
  "}"
].join("\n");

function cubeMesh() {
  var faces = [
    { n: [0, 0, 1], u: [1, 0, 0], v: [0, 1, 0], c: [0.0, 0.9, 1.0] },
    { n: [0, 0, -1], u: [-1, 0, 0], v: [0, 1, 0], c: [1.0, 0.2, 0.8] },
    { n: [1, 0, 0], u: [0, 0, -1], v: [0, 1, 0], c: [0.6, 1.0, 0.2] },
    { n: [-1, 0, 0], u: [0, 0, 1], v: [0, 1, 0], c: [1.0, 0.7, 0.1] },
    { n: [0, 1, 0], u: [1, 0, 0], v: [0, 0, -1], c: [0.5, 0.4, 1.0] },
    { n: [0, -1, 0], u: [1, 0, 0], v: [0, 0, 1], c: [1.0, 0.3, 0.3] }
  ];
  var corners = [[-1, -1], [1, -1], [1, 1], [-1, 1]];
  var pos = [], col = [], idx = [];
  faces.forEach(function (f, i) {
    corners.forEach(function (q) {
      for (var k = 0; k < 3; k++) pos.push((f.n[k] + f.u[k] * q[0] + f.v[k] * q[1]) * 0.5);
      col.push(f.c[0], f.c[1], f.c[2]);
    });
    var b = i * 4;
    idx.push(b, b + 1, b + 2, b, b + 2, b + 3);
  });
  return { key: "cube", mode: "triangles", attribs: { aPos: { size: 3, data: pos }, aCol: { size: 3, data: col } }, indices: idx };
}

function apply() {
  if (!kb.supports("gl")) { kb.toast("GLSL is not supported in this build"); return; }
  var c = kb.config();
  var size = kb.keyboardSize();
  var haveSize = size.width > 0 && size.height > 0;

  if (c.strip) {
    var h = Math.round(c.height * 2.2 + 8);
    var item = {
      type: "gl", frag: STRIP, fps: 30,
      uniforms: { uColor: c.color, uRgb: c.rgb ? 1 : 0, uHeight: c.height }
    };
    // a strip sized to the bottom of the keyboard is much cheaper than a full-size layer
    if (haveSize) { item.x = 0; item.y = size.height - h; item.w = size.width; item.h = h; }
    kb.background([item]);
  } else kb.clearBackground();

  if (c.cube && haveSize) {
    kb.overlay([{ type: "gl", vert: CUBE_V, frag: CUBE_F, mesh: cubeMesh(), depth: true, fps: 30,
      x: size.width - 84, y: 6, w: 72, h: 72 }]);
  } else kb.clearOverlay();

  setTimeout(function () {
    var e = kb.glError(STRIP) || kb.glError(CUBE_F);
    if (e) kb.toast("GL: " + e);
  }, 1500);
}

apply();
setTimeout(apply, 600);            // the keyboard size may still be 0 at load
kb.on("show", apply);
kb.on("device", apply);
kb.on("option", apply);
