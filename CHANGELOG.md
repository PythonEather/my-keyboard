# Changelog

Expressive Keyboard Pro, an independent fork. Each version's full notes are in `release-notes/` and on its GitHub release.

## 11.2.21
- Fix: swipe typing no longer leaves the first key stretched and pressed (with its popup) for the whole swipe; the key is released as soon as the swipe starts
- Incognito: the keyboard now honors the app's own "no personalized learning" flag (private tabs in browsers): nothing is learned, mods see no text, system spellcheck stays off, and the incognito icon shows on the menu button and the space bar. The manual incognito toggle shows the same icon
- Animation packs (file version 2): export and import now carry your custom press animations plus the chosen press and popup animation, speed, panel transition and keyboard-open animation. Version 1 files still import
- Panel transitions are customizable (style, fade times, start size, slide distance, spring) with a live preview; the presets keep their old look
- New: keyboard opening animation (fade, grow or slide from below), off by default; plays each time the keyboard appears

## 11.2.20
- Built-in 3D engine: a `gl` layer can have `scene: { models, camera, lights, objects, ambient, fog }` and the keyboard draws it with its own lit renderer, with no shader code (a layer with a scene needs no `frag`)
- Models: `.obj` (with `.mtl`) and `.glb` / `.gltf` from a `.zip` mod, plus built-in shapes `cube`, `plane`, `sphere`, `cylinder`, `quad`; per-object `pos`, `rot`, `scale`, `visible` and `material` (color, emissive, specular, shininess, texture, unlit, doubleSided, alphaCut)
- Lights: up to 8 point and sun lights, ambient light and fog, lit per pixel
- Shared depth: the objects are drawn after the layer's shader into the same depth buffer, and a background shader can read the camera (`uViewProj`, `uInvViewProj`, `uCamPos`, `uCamForward`, `uCamRight`, `uCamUp`, `uCamParams`) and write `gl_FragDepth`
- `kb.scene(id, patch)` changes the camera, lights, fog or objects of a live scene; `kb.supports("scene3d")`
- New example plugin `gl_scene.js`; the plugin test tool accepts a `gl` layer with a `scene` and no `frag`
- Pictures of a scene share the layer's texture pool (up to 640 pictures)
- `PLUGINS.md` (new section **3D scenes**) and `README.md` updated

## 11.2.19
- Fix: Lua mods failed to load in the release app with `no non-static method LuaPluginRuntime.dispatchHost` (the code shrinker removed the method that the native Lua engine calls); the ProGuard rule now keeps it, like for QuickJS

## 11.2.18
- Lua 5.4 as a third script engine next to Rhino and QuickJS: a mod can be a `.lua` file with its manifest in a `--[=[ @plugin { … } ]=]` comment (or `"scriptEngine": "lua"` in a `.json` or `.zip`)
- Same `kb.*` API, permissions and call limits; tables stand for objects and lists, `kb.json`, `kb.storage`, `print` and `require` are built in
- Sandbox: no `io`, `os`, `debug`, `package` or file loading, text chunks only, 64 MB of memory, and a script that runs too long is stopped even inside `pcall`
- Exporting a Lua mod saves a `.lua` file; new example plugin `hello.lua`
- `PLUGINS.md` and `README.md` updated

## 11.2.17
- Fix: a picture chosen in a mod's image setting was always refused with "The picture could not be read" (the size check treated its normal empty answer as a failure)

## 11.2.16
- Picture settings for mods: a config option with `"type": "image"` lets the user pick a picture from the gallery; `kb.config().photo` goes straight into `textures: { iTex0: ... }`
- An empty texture source is now an empty slot (one transparent pixel) instead of a mistake, so a mod can draw a placeholder until a picture is chosen
- The picture is kept in the app's private storage (2048 px at most), is readable only by the mod that asked for it, and is deleted with the mod
- New example plugin `gl_photo.js` (a cube with your photo); `kb.supports("imageSettings")`; the plugin test tool runs a mod with and without a chosen picture
- `PLUGINS.md` and `README.md` updated

## 11.2.15
- Image textures in GLSL layers (`textures: { iTex0: "brick.png" }`), with wrap, filter and mipmap options
- In-app updates find the newest GitHub release with a signed APK and verify the download; new "Release APK" workflow
- Info notes, search field and update card match the glass look of the settings panel
- `PLUGINS.md` and `README.md` updated
