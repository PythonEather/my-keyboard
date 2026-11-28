# Expressive Keyboard Pro
![Expressive Keyboard Pro demo](docs/demo.gif)

> **This is an independent fork, not the original project.** It is based on
> [kotlalipopus/Expressive-Keyboard](https://github.com/kotlalipopus/Expressive-Keyboard) (MIT) and is maintained
> separately by [PythonEather](https://github.com/PythonEather). It has its own releases, version numbers and changes.
> Bugs found here should be reported here, not to the original author.

<p align="center">
  <img src="docs/showcase.gif" width="360" alt="Expressive Keyboard Pro showcase">
</p>

### What is different from the original

**Plugins and mods**
- Own screen for mods: fullscreen, landscape (`"display": "landscape"`) or a floating panel
- Real GLSL layers on every Android version the app installs on (8.0+): fragment and vertex shaders, meshes, instancing, feedback (the shader reads the previous frame), and automatic quality that lowers resolution and fps when the phone is slow or hot
- GLSL layers were first committed to this fork on 22 Nov 2026 (commit `544021f`, 21:12 UTC+8)
- **Picture settings for mods** (`"type": "image"`): the person using a mod picks a picture from the gallery in the mod's settings and the mod gives it to a shader as a texture (`kb.config().photo`); the picture stays in the app's own storage and only that mod can read it
- **Image textures in GLSL layers** (`textures: { iTex0: "brick.png" }`): pictures from the `.zip` (or `data:` URIs) as `sampler2D` uniforms, for textured 3D, sprites, noise and lookup-table colour effects; repeat, mirror, nearest and mipmaps (see `gl_textures.js`)
- Mod API additions: retained groups, host-side pan/zoom, GL shape batches
- **Built-in 3D engine** (`scene: { ... }` in a `gl` layer, `kb.scene` to change it): load `.obj` and `.glb` / `.gltf` models or use built-in shapes, a camera, point and sun lights, materials, fog, and objects that **share the depth buffer** with the layer's own shader; example `gl_scene.js`
- **Lua 5.4 as a third script engine**: write a mod as a `.lua` file (manifest in a `--[=[ @plugin { … } ]=]` comment) with the same `kb.*` API, a sandbox and the same time limits; example `hello.lua`
- Faster GL layer and QuickJS (idle frames skipped, uniform caching, 32-bit mesh indices)
- Music and other big files in a `.zip` mod: up to 256 MB, kept on disk and played straight from there
- Install a plugin from a link, and check all linked plugins for newer versions
- In-app updates (OTA): a new GitHub release with an APK shows up in Settings; the `Release APK` workflow publishes it for you when `versionName` goes up
- Plugin permission `media`: now playing, cover and player controls (needs Notification access for the details)
- Plugin permission `settings_layout`: a plugin can pin, reorder and hide pages on the Settings home screen
- Plugin permission `toolbar_layout`: a plugin can reorder and hide the buttons of the keyboard toolbar
- Security: red dot while a plugin uses a sensitive permission, and a reminder banner every few minutes (can be switched off)
- Import and export of custom animations; export of plugins (JSON and script)
- Press highlight slider (turn the color change of a pressed key down or off) and a soft fade between letters, numbers and symbols
- Your own plugins can live in [`plugins/`](plugins): the GitHub build runs each one in a Node mock of `kb` and compiles its shaders, so a broken plugin fails the build before you install it

**Effects and tools (Settings > Extra effects)**
- Press shockwave, tilt parallax, typing speed glow, and an edge swipe for one-hand mode, each with its own switch and sliders
- Themes by app and by time of day (day and night, at sunset or with system dark mode), without changing your own theme
- Layout profiles: save size, position, DPI and gaps under a name, switch, and share one as a file
- Typewriter and Clicky sounds, pitch per key, cursor ticks on the space bar, and a typing stats page that stays on the phone

**Look**
- Dynamic shadow: key shadows lean away from the tilt of the phone (Settings → Colors and background)
- Static frosted glass: the background picture is blurred once instead of live, on any Android version

**General**
- Renamed to **Expressive Keyboard Pro**
- `PLUGINS.md` and script log messages in English
- GitHub builds use one fixed signing key, so a newer build installs over an older one made by the same workflow

An Android keyboard built with Kotlin, Jetpack Compose and the Material 3 Expressive components
(`androidx.compose.material3` 1.5): `MaterialExpressiveTheme` with the expressive motion scheme,
`MaterialShapes`, `LoadingIndicator`, wavy progress indicators and connected toggle button groups.
Highly customizable and extensible with plugins and JavaScript mods.

<!-- Add 2–3 screenshots here, for example:
<p>
  <img src="screenshots/keyboard.png" width="250">
  <img src="screenshots/settings.png" width="250">
</p>
-->

## Download

Get the latest APK from [Releases](https://github.com/PythonEather/my-keyboard/releases/latest), or from the newest
successful **Build APK** run in the [Actions](https://github.com/PythonEather/my-keyboard/actions) tab (artifacts
`ExpressiveKeyboard-release-apk` and `ExpressiveKeyboard-debug-apk`).

Requires Android 8.0 or newer.

Also available on [Appteka](https://appteka.store/app/146r343798).

Website: https://pythoneather.github.io/my-keyboard/

A newer build installs over an older one only if both were signed with the same key;
an app signed with a different key has to be uninstalled first.

### Updates inside the app (OTA)

Settings → About → **Check for updates** (and once per launch of Settings, if switched on) asks GitHub for the newest release of
`PythonEather/my-keyboard` that has an APK attached. If its version is higher than the installed one, an update card appears; the app
downloads the APK, checks that it is this app and newer, and opens the system installer. It installs over the old version only when
both are signed with the same key (every build of the **Build APK** and **Release APK** workflows uses `signing/ci-debug.keystore`).

To publish a new version:

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` (for example `11.2.16`) and, if you like, add
   `release-notes/11.2.16.md` (shown in the update card).
2. Push to `main`. The **Release APK** workflow builds the release APK and creates the GitHub release `v11.2.16` with
   `ExpressiveKeyboard-11.2.16.apk` attached. Nothing is created twice: if the release already exists, the workflow does nothing.

Doing it by hand also works: **Releases → Draft a new release**, tag `v11.2.16`, attach an APK whose name ends in `.apk` and does not
contain `debug` or `unsigned`, and **Publish** (a pre-release is found too; a draft is not).

## Features

- **Typing:** word suggestions, autocorrect that learns from your undos (undo the same correction twice and the word is left alone), glide (swipe) typing
- **Languages:** 90+ layouts; Chinese (Pinyin, simplified and traditional) and Japanese (Romaji → Kana) input; Indic scripts (InScript)
- **Emoji:** all Unicode 16 emoji, search in English and Russian, skin tones, built-in styles (Twemoji, Noto, Blob, OpenMoji)
- **First-run setup:** new users pick a theme, how many expressive shapes they want and which features to keep
- **Designs:** Material 3 Expressive, Material 3 (Google guidelines), Liquid Glass, Classic, Neon, Neumorphism, Samsung-like, Nothing-like — one tap
- **Shapes:** lots of shapes, accents only, or no shapes at all (Settings → Colors & background)
- **App language:** Russian or English
- **Panels:** emoji, GIFs, clipboard, translator, voice input
- **Look:** Material You colors, themes and skins, custom fonts (.ttf/.otf), a photo from your gallery behind the keys, or a live video wallpaper; jelly push of the keys around a pressed key, a colour wave on tapped buttons
- **Layout:** key height and corner radius, spacing, keyboard width, one-handed mode, own keyboard DPI, grouped size settings
- **Feedback:** adjustable vibration and sound
- **Privacy:** incognito mode; suggestions and dictionary learning are turned off in password fields
- **Plugins and mods:** custom buttons, shortcuts, layouts, themes and JavaScript scripts that can draw and animate over the keyboard (including GLSL shaders and 3D), play music, press keys, change layouts on the fly and arrange the Settings screen and the toolbar

## Installation

1. Download and install the APK from [Releases](https://github.com/PythonEather/my-keyboard/releases/latest).
2. Open the app and enable the keyboard in system settings.
3. Select Expressive Keyboard Pro as your input method.

## Permissions

| Permission | Why |
|---|---|
| Internet | GIFs (Giphy), translator, downloading suggestion dictionaries, mods with the `network` permission |
| Microphone | Voice input |
| Vibration | Haptic feedback on key press |

Your typed text is not sent anywhere unless you use the translator yourself or install a mod
that has both network and text-reading permissions.

## Plugins and mods

Plugins are imported in **Settings → Plugins and mods**: from a file, from the clipboard or from a link.
The format and JavaScript API are described in [PLUGINS.md](PLUGINS.md) (in English). Built-in plugins live in
[`app/src/main/assets/plugins`](app/src/main/assets/plugins), examples (GLSL feedback, a 3D neon scene, a textured cube, a first mod) in
[`app/src/main/assets/examples`](app/src/main/assets/examples).

To test your own plugins before installing them (Node.js 20+ and `glslang`):

```bash
node tools/plugin-test/run.mjs
```

Put them in [`plugins/`](plugins) and the same check runs in the GitHub build.

## Building from source

You need Android Studio (or JDK 17 and Android SDK 35). The Expressive APIs come from
`material3:1.5.0-alpha13`, the newest version that still builds with compileSdk 35 and AGP 8.7.

```bash
git clone https://github.com/PythonEather/my-keyboard.git
cd my-keyboard
./gradlew assembleDebug
```

Install Android NDK (Side by side) version `28.2.13676358` from the Android Studio SDK Manager to build QuickJS and Lua.
The APK will be in `app/build/outputs/apk/debug/`.

No computer? Push to GitHub and the **Build APK** workflow (`.github/workflows/build.yml`) builds the release and
debug APKs for you; download them from the run's artifacts.

To enable GIF search, put your own Giphy key into `GIPHY_API_KEY` in
`app/src/main/java/com/expressive/keyboard/Common.kt`. Without a key, a built-in GIF set is used.

## License

[MIT](LICENSE)
