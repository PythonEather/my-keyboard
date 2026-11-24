/* @plugin
{
  "id": "gl.photo",
  "name": "GLSL: Cube with your photo",
  "version": "1.0",
  "description": "A spinning cube that wears a picture the user chooses in the plugin's settings (an image setting used as a GL texture)",
  "icon": "🖼️",
  "scriptEngine": "quickjs",
  "config": [
    { "id": "cube", "title": "Show the cube", "type": "toggle", "default": true },
    { "id": "photo", "title": "Photo on the cube", "type": "image", "description": "Choose a picture from the gallery. It is copied into the app (at most 2048 px) and only this plugin can read it.", "showIf": "cube" }
  ]
}
*/

// ---------------------------------------------------------------------------
// Pictures chosen by the user (kb.supports("imageSettings")):
//   1. Declare a setting of type "image" in the manifest: { "id": "photo", "type": "image", ... }.
//      The settings card shows the picture, a "Choose picture" button and a "Remove" button.
//   2. kb.config().photo is a short text ("user:photo:<stamp>") while a picture is chosen, and "" while there is none.
//      The text changes with every new picture. It is not a file name or a data: URI: pass it as it is.
//   3. Give it to a GL layer as a texture source: textures: { iTex0: kb.config().photo }.
//      An empty text is an empty slot: the shader gets a 1x1 transparent pixel (iTex0Size is 1 x 1), so no error is logged.
//   4. kb.on("option", ...) fires when the user chooses or removes the picture: draw again there.
// A plugin can only read pictures chosen in its own image settings, never other files of the phone.
// ---------------------------------------------------------------------------

var CUBE_V = [
  "attribute vec3 aPos;",
  "attribute vec2 aUv;",
  "uniform float iTime;",
  "varying vec2 vTex;",
  "varying float vShade;",
  "void main() {",
  "  float a = iTime * 0.9;",
  "  float b = iTime * 0.6;",
  "  mat3 ry = mat3(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a));",
  "  mat3 rx = mat3(1.0, 0.0, 0.0, 0.0, cos(b), sin(b), 0.0, -sin(b), cos(b));",
  "  vec3 p = rx * ry * aPos;",
  "  vTex = aUv;",
  "  vShade = 0.6 - p.z * 0.6;",
  "  gl_Position = vec4(p.x * 2.6, p.y * 2.6, p.z * 0.5, p.z + 3.2);",
  "}"
].join("\n");

var CUBE_F = [
  "precision mediump float;",
  "uniform sampler2D iTex0;",
  "uniform vec2 iTex0Size;",
  "varying vec2 vTex;",
  "varying float vShade;",
  "void main() {",
  "  vec4 t = texture2D(iTex0, vTex);",
  "  if (iTex0Size.x < 2.0) {",                                // no picture chosen yet: a grey checkerboard
  "    float c = mod(floor(vTex.x * 4.0) + floor(vTex.y * 4.0), 2.0);",
  "    t = vec4(vec3(0.25 + 0.15 * c), 1.0);",
  "  }",
  "  gl_FragColor = vec4(t.rgb * clamp(vShade, 0.3, 1.0), t.a);",
  "}"
].join("\n");

function cubeMesh() {
  var faces = [
    { n: [0, 0, 1], u: [1, 0, 0], v: [0, 1, 0] },
    { n: [0, 0, -1], u: [-1, 0, 0], v: [0, 1, 0] },
    { n: [1, 0, 0], u: [0, 0, -1], v: [0, 1, 0] },
    { n: [-1, 0, 0], u: [0, 0, 1], v: [0, 1, 0] },
    { n: [0, 1, 0], u: [1, 0, 0], v: [0, 0, -1] },
    { n: [0, -1, 0], u: [1, 0, 0], v: [0, 0, 1] }
  ];
  var corners = [[-1, -1], [1, -1], [1, 1], [-1, 1]];
  var pos = [], uv = [], idx = [];
  faces.forEach(function (f, i) {
    corners.forEach(function (q) {
      for (var k = 0; k < 3; k++) pos.push((f.n[k] + f.u[k] * q[0] + f.v[k] * q[1]) * 0.5);
      uv.push((q[0] + 1) / 2, (q[1] + 1) / 2);
    });
    var b = i * 4;
    idx.push(b, b + 1, b + 2, b, b + 2, b + 3);
  });
  return { key: "photo-cube", mode: "triangles", attribs: { aPos: { size: 3, data: pos }, aUv: { size: 2, data: uv } }, indices: idx };
}

function apply() {
  if (!kb.supports("imageSettings")) { kb.toast("This keyboard version has no image settings (11.2.16 or newer)"); return; }
  var c = kb.config();
  var size = kb.keyboardSize();
  if (c.cube && size.width > 0 && size.height > 0) {
    kb.overlay([{
      type: "gl", vert: CUBE_V, frag: CUBE_F, mesh: cubeMesh(), depth: true, fps: 30,
      textures: { iTex0: { src: c.photo || "", mipmap: true } },
      x: size.width - 100, y: 6, w: 92, h: 92
    }]);
  } else kb.clearOverlay();

  setTimeout(function () {
    var e = kb.glError(CUBE_F);
    if (e) kb.toast("GL: " + e);
  }, 1500);
}

apply();
setTimeout(apply, 600);            // the keyboard size may still be 0 at load
kb.on("show", apply);
kb.on("device", apply);
kb.on("option", apply);
