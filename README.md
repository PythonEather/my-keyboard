# Expressive Keyboard Pro

> **This is an independent fork, not the original project.** It is based on
> [kotlalipopus/Expressive-Keyboard](https://github.com/kotlalipopus/Expressive-Keyboard) (MIT) and is maintained
> separately by [PythonEather](https://github.com/PythonEather). It has its own releases, version numbers and changes.
> Bugs found here should be reported here, not to the original author.

### What is different from the original

- Renamed to **Expressive Keyboard Pro**
- Landscape full-screen mode for mods (`"display": "landscape"`)
- Import and export of custom animations; export of plugins (JSON and script)
- `PLUGINS.md` and script log messages in English
- Faster GL layer and QuickJS (idle frames skipped, uniform caching, 32-bit mesh indices)
- Mod API additions: retained groups, host-side pan/zoom, GL shape batches
- Dynamic shadow: key shadows lean away from the tilt of the phone (Settings → Colors and background)
- Static frosted glass: the background picture is blurred once instead of live, on any Android version
- Plugin permission `media`: now playing, cover and player controls (needs Notification access for the details)
- Plugin permission `settings_layout`: a plugin can pin, reorder and hide pages on the Settings home screen
- Security: red dot while a plugin uses a sensitive permission, and a reminder banner every few minutes (can be switched off)


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

Get the latest APK from [Releases](https://github.com/PythonEather/my-keyboard/releases/latest).

Requires Android 8.0 or newer.

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
- **Plugins and mods:** custom buttons, shortcuts, layouts, themes and JavaScript scripts that can draw and animate over the keyboard, press keys and change layouts on the fly

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

Plugins are imported in **Settings → Plugins and mods**. The format and JavaScript API are described
in [PLUGINS.md](PLUGINS.md) (in English); examples live in [`app/src/main/assets/plugins`](app/src/main/assets/plugins).

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

To enable GIF search, put your own Giphy key into `GIPHY_API_KEY` in
`app/src/main/java/com/expressive/keyboard/Common.kt`. Without a key, a built-in GIF set is used.

## License

[MIT](LICENSE)
