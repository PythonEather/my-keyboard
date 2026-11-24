/* @plugin
{
  "id": "gl.feedback",
  "name": "GLSL: Feedback trails + instanced stars",
  "version": "1.0",
  "description": "Render-to-texture (the shader reads the previous frame as iPrev) and instancing (many copies of one mesh in one draw call)",
  "icon": "🌀",
  "scriptEngine": "quickjs",
  "config": [
    { "id": "trails", "title": "Finger trails (feedback)", "type": "toggle", "default": true },
    { "id": "stars", "title": "Twinkling stars (instancing, needs OpenGL ES 3)", "type": "toggle", "default": true },
    { "id": "debug", "title": "Show the GL performance line", "type": "toggle", "default": false }
  ]
}
*/

// Trails: every frame the old picture fades a little (iPrev * 0.94) and a soft dot is added under the finger.
var TRAIL = [
  "precision mediump float;",
  "varying vec2 vUv;",
  "uniform sampler2D iPrev;",
  "uniform vec3 iResolution;",
  "uniform vec3 iTouch;",
  "uniform float iTime;",
  "void main() {",
  "  vec4 old = texture2D(iPrev, vUv) * 0.94;",
  "  vec2 p = vUv * iResolution.xy;",
  "  vec2 t = vec2(iTouch.x, iResolution.y - iTouch.y);",   // iTouch has its origin at the top-left corner
  "  float a = iTouch.z * smoothstep(36.0, 0.0, distance(p, t));",
  "  vec3 col = 0.5 + 0.5 * vec3(sin(iTime * 2.0), sin(iTime * 2.0 + 2.1), sin(iTime * 2.0 + 4.2));",
  "  gl_FragColor = old + vec4(col * a, a);",   // premultiplied alpha
  "}"
].join("\n");

// Stars: one quad, STAR_COUNT copies. aOff is per copy (divisor 1): x, y in -1..1 and the size.
var STAR_V = [
  "#version 300 es",
  "in vec2 aPos;",
  "in vec3 aOff;",
  "uniform vec3 iResolution;",
  "uniform float iTime;",
  "out vec2 vUv;",
  "out float vTw;",
  "void main() {",
  "  vUv = aPos * 0.5 + 0.5;",
  "  vTw = 0.5 + 0.5 * sin(iTime * 2.0 + float(gl_InstanceID) * 1.7);",
  "  vec2 p = aOff.xy + aPos * aOff.z * (0.6 + 0.4 * vTw) * vec2(iResolution.y / iResolution.x, 1.0);",
  "  gl_Position = vec4(p, 0.0, 1.0);",
  "}"
].join("\n");

var STAR_F = [
  "#version 300 es",
  "precision mediump float;",
  "in vec2 vUv;",
  "in float vTw;",
  "out vec4 o;",
  "void main() {",
  "  float d = length(vUv * 2.0 - 1.0);",
  "  float a = smoothstep(1.0, 0.1, d) * (0.35 + 0.65 * vTw);",
  "  o = vec4(vec3(1.0, 0.92, 0.65) * a, a);",
  "}"
].join("\n");

var STAR_COUNT = 40;

function starMesh() {
  var seed = 12345, off = [];
  function rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; }
  for (var i = 0; i < STAR_COUNT; i++) off.push(rnd() * 2 - 1, rnd() * 2 - 1, 0.02 + rnd() * 0.05);
  return {
    key: "stars", mode: "strip", instances: STAR_COUNT,
    attribs: {
      aPos: { size: 2, data: [-1, -1, 1, -1, -1, 1, 1, 1] },
      aOff: { size: 3, divisor: 1, data: off }
    }
  };
}

function apply() {
  var c = kb.config();
  if (!kb.supports("gl")) { kb.toast("GLSL is not supported on this phone"); return; }
  if (c.trails) {
    // scale 0.5 is plenty for a soft trail; the host may lower it further when the phone is slow or hot
    kb.background([{ type: "gl", id: "trails", frag: TRAIL, feedback: true, fps: 30, scale: 0.5 }]);
  } else kb.clearBackground();
  if (c.stars) {
    kb.overlay([{ type: "gl", id: "stars", vert: STAR_V, frag: STAR_F, mesh: starMesh(), fps: 24, scale: 0.75 }]);
  } else kb.clearOverlay();
  kb.glDebug(!!c.debug);
  setTimeout(function () {
    var e = kb.glError(TRAIL) || kb.glError(STAR_F);
    if (e) kb.toast("GL: " + e);
  }, 1500);
}

apply();
kb.on("show", apply);
kb.on("option", apply);
