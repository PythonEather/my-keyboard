# Changelog

Expressive Keyboard Pro, an independent fork. Each version's full notes are in `release-notes/` and on its GitHub release.

## 11.2.26
- Neighbour push (Settings > Animations > Key press): a pressed key pushes the keys around it away and they spring back like jelly. Off by default (`key_push`). Sliders for push strength, how many rings of keys move, jelly bounce and speed; neighbours can be pushed away, pulled in or swirled, and squashed a little. It only moves the drawing of the keys, the touch areas stay where they are, and nothing runs while no key is pressed
- Size and position is now grouped: Keys (size and shape), Keyboard frame and margins, Bar above the keys, Keyboard scale (DPI). Letter size, number row height, Enter key width and top bar thickness are also here now
- Keyboard scale (DPI): an own DPI for the keyboard only (320 to 560 presets and a slider, 120 to 640, `custom_dpi`, 0 = the system value). Everything inside the keyboard rescales by itself
- Live wallpaper: pick a video from the gallery (up to 60 MB) as a muted looping background under the keys (Colors and background) and behind the Settings pages (Settings home). It replaces the photo while set, pauses when the keyboard is closed and does not play in battery saver. Glass themes do not see through to the video
- Paste offer above the toolbar: new switch in Settings > Clipboard (`clip_offer_top`). What you just copied shows in its own row above the toolbar instead of replacing it. Off by default
- Fix: mod fullscreen and landscape mods no longer show a white strip at the screen edge, and the keyboard window now extends under the status bar while a mod is fullscreen

## 11.2.25
- Space bar cursor: two new sliders in Settings > Gestures, "Space bar cursor speed" (left and right) and "Up / down cursor speed" (lines), from 0.5x to 3x, default 1x so nothing changes until you move them (`space_cursor_speed`, `space_line_speed`)
- Fix: the top bar no longer shows the toolbar icons through the suggestions when it switches between them. The old row now fades out before the new one fades in, and the suggestion pills are no longer clipped to square edges during the swap
- Updater: the downloaded update stays on the phone. If you tap outside the install window, tap Update again and the installer opens right away, with no new download. A half-finished download is detected and started over
- Updater: if the update is signed with a different key than the installed app, you now get a clear message instead of Android's "Couldn't install the update". The fix is to uninstall the app and install the new version again
- Updater: the three file check messages now have English and Spanish text, they were Russian only

## 11.2.24
- Lua mods: values now go to Kotlin as Java objects instead of JSON text (about 7 to 9 times faster on big tables)

## 11.2.23
- Pressed key highlight: new slider in Settings > Animations > Key press (`press_highlight`, 0 to 1). It scales the color change and the glow of a pressed key for the plain keys and for every theme material (solid, neumorph, outline, glass, bevel, pixel). At Off the key keeps its resting color and the label color does not flip; the press animation itself still plays
- Soft fade between letters, numbers and symbols: new slider in Settings > Animations > Panels (`page_switch_anim`, default 50%). When the key page or the number pad changes, the keys fade from a lighter state back to full in about 140 ms (scaled by the animation speed). It is one layer, not a second copy of the keys, so it costs nothing while typing. Off, or the panel animations switch turned off, gives an instant swap
- Autocorrect memory: undoing an autocorrection with backspace now counts toward learning the word (`WordPredictorEngine.UNDO_BOOST`). A word that looks like a typo of a common word is learned after two undos and is then left alone for good, even after the 10 minute pause; a single undo still only pauses the correction. Words that do not look like a typo (names, slang) are learned after one undo. Nothing is learned in incognito mode or in password fields, as before
- New test `LearnedTypoTest.twoUndosStopTheCorrectionForGood`
- `PLUGINS.md` (settings keys) and `README.md` updated

## 11.2.22
- Space bar: new "Swipe up / down" setting (Settings > Gestures > Space): move the cursor between lines or change the language.
- Fast typing: stray symbols like * and # no longer appear when your finger flicks while typing quickly.
- Effects mod: sparks are now off by default and fully customizable (amount, size, colors, fall, lifetime, speed), with new Sparks, Sand and Snow presets.

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
