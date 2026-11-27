# Changelog

Expressive Keyboard Pro, an independent fork. Each version's full notes are in `release-notes/` and on its GitHub release.

## 11.2.31
- Home screen redesign on phones: the pages are now compact two-column cards instead of long rows, so Home needs about half the scrolling. "What's new" stays as one big card at the top
- Cards fade and slide in one after another when the app opens. It plays once per app run, so coming back to Home does not replay it. Cards squish a little and change shape when pressed
- The language switcher moved from the top to the bottom of Home, so it no longer takes a big card on every visit
- Tablets and the two-pane layout keep the list as before

## 11.2.30
> ## ⚠️ READ THIS FIRST: 11.2.30 uses a new signing key
>
> **You cannot install this version over your current app.** Android will say "App not installed" or "signed with a different key", and the in-app Update button will show the same message. Nothing is broken, this is expected and happens only once.
>
> **Why:** until now every release was signed with a key that sits in the public repository, so anyone could build an APK that Android treats as an update to your keyboard. From 11.2.30 the app is signed with a private key that only the developer has. Android does not allow changing the key without a reinstall.
>
> **What to do** (2 minutes, you keep your settings)
>
> 1. In your **current** app open **Settings > Backup > Full backup** and save the `.zip` to **Downloads** (or Google Drive). Do not leave it inside the app's own folder, it is deleted with the app. Tick "API keys" if you want them back.
> 2. **Uninstall** Expressive Keyboard Pro.
> 3. **Install** `ExpressiveKeyboard-11.2.30.apk` from this page.
> 4. Turn the keyboard on again in Android's settings, open the app, then **Settings > Backup > Restore** and pick your `.zip`.
>
> After this, all later updates install normally over each other.

- New release signing key (see above). The key and its password are kept in GitHub secrets, not in the code
- The release build now stops and publishes nothing if the APK was not signed with the new key

## 11.2.29
- Full backup (Settings > Backup > Full backup): save settings, mods and their data, themes and looks, layout profiles, pictures, fonts, sounds, icons and dictionaries into one `.zip`, and restore it later
- You choose whether typed data is included (learned words, clipboard, typing stats; on by default) and API keys (off by default; they are stored unprotected in the file). The `.zip` has no password, so keep it private
- Restore checks the whole file before changing anything, replaces current data and closes the app. Only restore your own backup. Android's own permissions and enabling the keyboard are not part of the backup

## 11.2.28
- Extra effects page (Settings > Extra effects). Every effect has its own switch and sliders and is off by default, and nothing runs while its switch is off
- Press shockwave (`shockwave`): a soft ring spreads from the pressed key and nudges the keys it passes, like the neighbour push but with a visible ripple. Sliders for wave speed (`shockwave_speed`), strength (`shockwave_strength`), how far it goes (`shockwave_reach`) and key ripple (`shockwave_ripple`, 0 = ring only), and a switch for the ring itself (`shockwave_ring`). Visual only, touch areas do not move
- Tilt parallax (`tilt_parallax`): the keys shift a little when the phone tilts, so the keyboard looks 3D. It extends the tilt sensor that dynamic shadow already uses. Strength (`tilt_parallax_strength`) and invert (`tilt_parallax_invert`)
- Typing speed glow (`typing_glow`): a neon strip on the top or bottom edge (`typing_glow_edge`) that gets brighter and whose light runs faster the quicker you type, and calms down when you stop. Settings: colour accent or rainbow, brightest glow, typing speed for full glow, calm down time, thickness. Frames run only while the strip is warm
- Edge swipe for one hand (`edge_swipe`): swipe in from the left or right edge of the keyboard to shrink it to that side. A swipe from the other edge goes back to full width or moves to that side (`edge_swipe_opposite`). Edge zone width, swipe length and a tick on switching. The button and the setting still work
- Themes by app and time (Settings > Themes by app and time): save the current theme as a look, pick a look for each app (`per_app_themes`), and a day look and a night look (`day_night`) that switch by times you choose, at sunset (type your latitude and longitude once, no location permission) or with system dark mode. A look is only worn while you type: your own theme and the Settings screen are not changed. An app rule wins over day and night, and "My normal theme" in an app rule keeps that app plain at night
- Layout profiles (Settings > Layout profiles): save key size, gaps, margins, one-hand and floating setup, DPI and the emoji key under a name, switch between profiles and export one as a `.json` file for a friend. Importing reads only layout values of the right type
- Sound packs: new Typewriter and Clicky sounds next to Soft and Mechanical. Pitch per key (`sound_key_pitch`): each key gets its own small fixed pitch. Random change on every press (`sound_wobble`, 50% is the old behaviour)
- Space bar cursor ticks: a switch (`space_ticks`, on as before), three strengths (`space_tick_strength`) and a switch for line moves (`space_tick_lines`). Line moves feel one step stronger
- Typing stats (Settings > Typing stats, off by default, `typing_stats`): keys today, average and best speed, streaks, the last 7 days and the most used words. Everything stays on the phone, nothing is recorded in password fields or incognito, only words made of letters are kept, and one button deletes it all. The old `TypingStats` class is unchanged: it is only the 2 second window behind the plugin event `typing`
- New tests: `ExtraFeaturesTest`

## 11.2.27
- About screen like the Google Play Store (Material 3 Expressive): big app icon, name, version and language count, a full-width pill button (Check for updates, Update to X, Downloading N%) and an "About this app" row that opens the feature list. While an update downloads, a wavy circular progress ring runs around the app icon. The update notes show three lines with Read more / Show less
- Colour wave on buttons (Settings > Animations > Key press, `button_wash`, on by default): tapping ?123 / ABC, the symbols page key, emoji, language, Shift, Enter, a toolbar button or the right-hand header button plays a soft colour wave that slides across it. Speed and strength sliders (`button_wash_speed`, `button_wash_strength`). Nothing runs while no button is tapped
- Emoji button: width, icon size and place (Settings > Size and position > Emoji button). Width 70% to 220% (`emoji_key_width`), icon 70% to 180% (`emoji_key_icon_scale`), place next to language, first in the bottom row or after the space bar (`emoji_key_pos`). The space bar takes whatever width is left
- Plugin changelog page: a long version history in a plugin description, or the new manifest field `changelog` (text, list of lines or list of {version, date, notes}), moves out of the plugin card. The card shows the short description and a What's new teaser; Full changelog opens a sheet with the latest version open and older ones folded, with Expand all / Collapse all
- Clipboard categories: the clipboard panel has filter chips (All, Images, Links and your own). Long-press a clip to move it to a category or take it out; a clip in a category is pinned so Clear and the storage time do not remove it. Quick names (Passwords, Work, Personal, Codes, Addresses, Notes) come with the + chip, any other name is made in Settings > Clipboard > Clipboard categories. A category can hide its text behind dots (not encryption); the eye button in the panel shows it again

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
