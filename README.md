# Expressive Keyboard Pro

> **This is an independent fork, not the original project.** It is based on
> [kotlalipopus/Expressive-Keyboard](https://github.com/kotlalipopus/Expressive-Keyboard) (MIT) and is maintained
> separately by [PythonEather](https://github.com/PythonEather). It has its own releases, version numbers and changes.
> Bugs found here should be reported here, not to the original author.

### What is different from the original

**Plugins and mods**
- Own screen for mods: fullscreen, landscape (`"display": "landscape"`) or a floating panel
- Real GLSL layers on every Android version the app installs on (8.0+): fragment and vertex shaders, meshes, instancing, feedback (the shader reads the previous frame), and automatic quality that lowers resolution and fps when the phone is slow or hot
- Mod API additions: retained groups, host-side pan/zoom, GL shape batches
- Faster GL layer and QuickJS (idle frames skipped, uniform caching, 32-bit mesh indices)
- Music and other big files in a `.zip` mod: up to 256 MB, kept on disk and played straight from there
- Install a plugin from a link, and check all linked plugins for newer versions
- Plugin permission `media`: now playing, cover and player controls (needs Notification access for the details)
- Plugin permission `settings_layout`: a plugin can pin, reorder and hide pages on the Settings home screen
- Plugin permission `toolbar_layout`: a plugin can reorder and hide the buttons of the keyboard toolbar
- Security: red dot while a plugin uses a sensitive permission, and a reminder banner every few minutes (can be switched off)
- Import and export of custom animations; export of plugins (JSON and script)
- Your own plugins can live in [`plugins/`](plugins): the GitHub build runs each one in a Node mock of `kb` and compiles its shaders, so a broken plugin fails the build before you install it

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

Requires Android 8.0 or newer. A newer build installs over an older one only if both were signed with the same key;
an app signed with a different key has to be uninstalled first.

## Features

- **Typing:** word suggestions, autocorrect, glide (swipe) typing
- **Languages:** 90+ layouts; Chinese (Pinyin, simplified and traditional) and Japanese (Romaji → Kana) input; Indic scripts (InScript)
- **Emoji:** all Unicode 16 emoji, search in English and Russian, skin tones, built-in styles (Twemoji, Noto, Blob, OpenMoji)
- **First-run setup:** new users pick a theme, how many expressive shapes they want and which features to keep
- **Designs:** Material 3 Expressive, Material 3 (Google guidelines), Liquid Glass, Classic, Neon, Neumorphism, Samsung-like, Nothing-like — one tap
- **Shapes:** lots of shapes, accents only, or no shapes at all (Settings → Colors & background)
- **App language:** Russian or English
- **Panels:** emoji, GIFs, clipboard, translator, voice input
- **Look:** Material You colors, themes and skins, custom fonts (.ttf/.otf), a photo from your gallery behind the keys
- **Layout:** key height and corner radius, spacing, keyboard width, one-handed mode
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
[`app/src/main/assets/plugins`](app/src/main/assets/plugins), examples (GLSL feedback, a 3D neon scene, a first mod) in
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

Install Android NDK (Side by side) version `28.2.13676358` from the Android Studio SDK Manager to build QuickJS.
The APK will be in `app/build/outputs/apk/debug/`.

No computer? Push to GitHub and the **Build APK** workflow (`.github/workflows/build.yml`) builds the release and
debug APKs for you; download them from the run's artifacts.

To enable GIF search, put your own Giphy key into `GIPHY_API_KEY` in
`app/src/main/java/com/expressive/keyboard/Common.kt`. Without a key, a built-in GIF set is used.

## License

[MIT](LICENSE)
