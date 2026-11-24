# Your plugins

Put your own plugins here. The GitHub workflow runs each one in a Node mock of `kb` and compiles its shaders
(`node tools/plugin-test/run.mjs`), so a broken plugin fails the build before you install it.

Accepted: a `.js` with a `/* @plugin {...} */` comment, a `.json` with a `"script"` field,
or a folder with `plugin.json` and `main.js` (plus any other files: other `.js` for `kb.require`, audio, data).
