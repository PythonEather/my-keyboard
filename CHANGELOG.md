# Changelog

Expressive Keyboard Pro, an independent fork. Each version's full notes are in `release-notes/` and on its GitHub release.

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
