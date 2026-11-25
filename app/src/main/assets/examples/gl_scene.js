/* @plugin
{
  "id": "gl.scene3d",
  "name": "3D scene: lit shapes",
  "version": "1.0",
  "description": "Test of the built-in 3D engine: a cube, a ball and a cylinder on a floor with a sun, a point light, fog and an orbiting camera. No shader, no model files.",
  "icon": "🎲",
  "scriptEngine": "quickjs",
  "config": [
    { "id": "orbit", "title": "Camera orbit", "type": "toggle", "default": true },
    { "id": "fog", "title": "Fog", "type": "toggle", "default": true }
  ]
}
*/

// A gl element with a `scene` and no `frag` is a pure 3D layer: the keyboard draws the objects (lit, with depth) and nothing else.
// Shapes are built in: "cube", "plane", "sphere", "cylinder", "quad". A model from the .zip works the same way:
//   models: { ship: { src: "ship.glb", fit: 1.5, center: "base" } }, objects: [{ id: "s", model: "ship" }]
// kb.scene(id, patch) changes only what the patch names, so moving the camera is cheap.

var LAYER = "demo";
var timer = null;
var angle = 0;

function describe(withFog) {
  return {
    camera: { pos: [0, 1.8, 4.5], target: [0, 0.4, 0], fov: 55 },
    ambient: "#2a2e40",
    fog: withFog ? { color: "#0b0b14", density: 0.07 } : null,
    lights: [
      { type: "sun", dir: [-0.4, -1, -0.3], color: "#fff4e0", intensity: 0.8 },
      { type: "point", pos: [1.2, 1.6, 1.4], color: "#ff6a3d", intensity: 2.5, range: 7 }
    ],
    objects: [
      { id: "floor", model: "plane", scale: [12, 1, 12], material: { color: "#2a2f3a", specular: 0.1 } },
      { id: "cube", model: "cube", pos: [-1.2, 0.5, 0], material: { color: "#00e5ff", specular: 0.6, shininess: 48 } },
      { id: "ball", model: "sphere", pos: [0, 0.5, 0.2], material: { color: "#ff3d7f", specular: 1.2, shininess: 96 } },
      { id: "post", model: "cylinder", pos: [1.2, 0.75, 0], scale: [0.6, 1.5, 0.6], material: { color: "#b6ff3d", specular: 0.3 } }
    ]
  };
}

function stop() {
  if (timer) { clearInterval(timer); timer = null; }
}

function apply() {
  var c = kb.config();
  stop();
  if (!kb.supports("scene3d")) { kb.toast("This keyboard version has no 3D scenes (11.2.20 or newer)"); return; }
  kb.overlay([{ type: "gl", id: LAYER, fps: 30, scale: 0.75, scene: describe(c.fog !== false) }]);
  if (c.orbit !== false) {
    timer = setInterval(function () {
      angle += 0.025;
      kb.scene(LAYER, {
        camera: { pos: [Math.sin(angle) * 4.5, 1.8, Math.cos(angle) * 4.5] },
        objects: { cube: { rot: [0, angle * 40, 0] } }
      });
    }, 50);
  }
}

setTimeout(apply, 600);
kb.on("show", apply);
kb.on("hide", stop);
kb.on("option", apply);
