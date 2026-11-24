/* @plugin
{
  "id": "gl.textures",
  "name": "GLSL: Textured 3D cube",
  "version": "1.0",
  "description": "Image textures in a GLSL layer: a spinning cube covered with a brick picture (textures: { iTex0: ... }, wrap repeat, nearest filter)",
  "icon": "🧱",
  "scriptEngine": "quickjs",
  "config": [
    { "id": "cube", "title": "Textured cube", "type": "toggle", "default": true },
    { "id": "tiles", "title": "Bricks per face", "type": "slider", "min": 1, "max": 6, "step": 1, "default": 2, "showIf": "cube" }
  ]
}
*/

// ---------------------------------------------------------------------------
// Textures in a GL layer (kb.supports("glTextures")):
//   { type: "gl", frag, textures: { iTex0: "brick.png" } }          file of the plugin's .zip
//   { type: "gl", frag, textures: { iTex0: "data:image/png;base64,..." } }   or a data: URI, as below
//   { type: "gl", frag, textures: { iTex0: { src, wrap: "clamp" | "repeat" | "mirror", filter: "linear" | "nearest", mipmap: true } } }
// The shader declares `uniform sampler2D iTex0;` and, if it wants the size in pixels, `uniform vec2 iTex0Size;`.
// Up to 4 textures per layer. Pixel (0,0) is the bottom-left corner, like vUv; colours are premultiplied by alpha.
// In a .zip mod write the file name (textures: { iTex0: "img/brick.png" }): the file is read once, not sent again with every kb.overlay.
// ---------------------------------------------------------------------------

// A 16x16 brick picture, made small enough to live in the script. A real mod would ship brick.png in its .zip.
var BRICK = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAABUUlEQVR42q3SR1YEMQwE0L4QOcOCnOGGQA859MCQYcGSBbcy/qb1Xu+HhcY9klyqKqu62phMvdWxdLM5lR5359Lx8kg6WRlN7weL6ef7K91vz6Q6/7/OfQ87s6Xez7mztfFSr+LCbQYQvs9z0RkA8vqAAqnbngJgqgh0p8BIg5xmJ7CPDILx5frEH4CfYaJCDyJ0CfQwMEEO7afM8DT3dOsC84oZDKo7mp/35osHIWuQzzCt2ZougOFN9bq/kLzEXS5ocMk3UCzof8s9wQC4O2H08B6EXm+L3ufhUmH0kidpwEadDBNJ7bf0CwBqIYOZQOLdNdDroh66DSTJoALAZZcFfbSb1nQ8cQGT2FDnxb/tgUkmoyoBHX0eyNMfmxqSsAhvKk3WNjRpIOMog8Rz8mnQAjDRUEPsRtkDSL32nU32xkCF3W9a/erAAKsxdWgPfgGfTYXbhEEMEAAAAABJRU5ErkJggg==";

var CUBE_V = [
  "attribute vec3 aPos;",
  "attribute vec2 aUv;",
  "uniform float iTime;",
  "uniform float uTiles;",
  "varying vec2 vTex;",
  "varying float vShade;",
  "void main() {",
  "  float a = iTime * 0.9;",
  "  float b = iTime * 0.6;",
  "  mat3 ry = mat3(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a));",
  "  mat3 rx = mat3(1.0, 0.0, 0.0, 0.0, cos(b), sin(b), 0.0, -sin(b), cos(b));",
  "  vec3 p = rx * ry * aPos;",
  "  vTex = aUv * uTiles;",                                  // uv above 1 repeats the picture because of wrap: "repeat"
  "  vShade = 0.6 - p.z * 0.6;",
  "  gl_Position = vec4(p.x * 2.6, p.y * 2.6, p.z * 0.5, p.z + 3.2);",
  "}"
].join("\n");

var CUBE_F = [
  "precision mediump float;",
  "uniform sampler2D iTex0;",
  "varying vec2 vTex;",
  "varying float vShade;",
  "void main() {",
  "  vec4 t = texture2D(iTex0, vTex);",                      // premultiplied, so it can be returned as it is once shaded
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
  return { key: "tex-cube", mode: "triangles", attribs: { aPos: { size: 3, data: pos }, aUv: { size: 2, data: uv } }, indices: idx };
}

function apply() {
  if (!kb.supports("glTextures")) { kb.toast("This keyboard version has no GL textures"); return; }
  var c = kb.config();
  var size = kb.keyboardSize();
  if (c.cube && size.width > 0 && size.height > 0) {
    kb.overlay([{
      type: "gl", vert: CUBE_V, frag: CUBE_F, mesh: cubeMesh(), depth: true, fps: 30,
      uniforms: { uTiles: c.tiles },
      textures: { iTex0: { src: BRICK, wrap: "repeat", filter: "nearest" } },
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
