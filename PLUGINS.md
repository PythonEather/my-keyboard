# Plugins and mods for Expressive Keyboard

A plugin is imported in **Settings → Plugins and mods**, from a file or from the clipboard. There are three kinds:

| File | What is inside |
|---|---|
| `.json` | A declarative plugin: actions, shortcuts, symbols, themes, skins, settings, layouts, icons, languages. It may also contain a script in the `script` field |
| `.js` | A **mod**: JavaScript, with the manifest written as JSON in a `/* @plugin { … } */` comment at the top of the file |
| `.zip` | A mod with files: `plugin.json` + `main.js` + images and data. Small files (images, data) take up to 8 MB together. Audio and any file of 256 KB or more is a **big file**: it is kept on disk, not in memory, and the archive may hold up to 256 MB of them (see "Big files and music") |

A mod script gets the `kb` object. It can react to key presses and words, insert and change text,
add its own panels, buttons, keys and suggestions, store data, use the network, change icons, language and settings,
**draw and animate anything on top of the keyboard**, find out where each key is, "press" keys, and change the layout
and the look on the fly. For example, you can make a character that walks over the keys and types (example below).
The script runs in a sandbox: it has no access to Java or to other apps' files (each mod has its own folder, `kb.files`);
anything that touches text, the network, links and AI needs a permission; and every script call has a time limit (300 ms by default, up to 2 s with `callBudgetMs`; loading — 2 s),
so a stuck mod cannot freeze the keyboard. The limit is per call, not per frame: drawing does not run in the script (see below). Errors and `console.log` output are shown in the plugin card ("Script log").

Scripts run on Rhino by default. To pick QuickJS for a particular mod, add
`"scriptEngine": "quickjs"` to its manifest. The field works for `.json`, for the `@plugin` comment in `.js`
and for the manifest inside a `.zip`. A mod can also be written in Lua 5.4 (`"scriptEngine": "lua"`, or a `.lua` file, see *Mods in Lua*).
All engines share the same `kb.*` API and can run at the same time.

Built-in examples: the mods Notes, Calculator, Smart quotes «», Studio (piano, drawing on the keyboard, an input field, text-to-speech, its own settings; `.js`) and Effects (particles, liquid, a shader, tilt, settings tabs and presets; `.js`), and the skins Liquid Glass, Neon and
Neumorphism (`app/src/main/assets/plugins/`). Templates: **Save mod example (.js)** and **Save plugin example**.

On the keyboard, plugins are opened with the 🧩 button on the toolbar (the grid button at the top left).

**Exporting.** Every plugin card has an **Export plugin** button (it saves the plugin exactly as it is stored: JSON with its script and files)
and, for mods with a script, an **Export script (.js)** button (the script with its manifest comment, ready to edit and import again).
If you delete a plugin by accident, import the exported file with **Import from file** to get it back. Built-in plugins can be exported too.

## What Expressive Keyboard Pro can do

Plugins here are not limited to a few buttons, to Android 13+ shaders or to a 300 ms script. A short map of what exists
(each feature has its own section below; `kb.supports(name)` tells a mod what the current phone can do):

| Area | What it gives you | Android version |
|---|---|---|
| **GLSL layers** (`type: "gl"`) | Your own OpenGL ES 2.0 / 3.0 fragment **and vertex** shaders, meshes, depth buffer, 1–60 fps, own render thread, feedback (the shader reads the previous frame), instancing, automatic quality when the phone is slow or hot. Full-screen effects and real 3D | **8+** (the app minimum) |
| **AGSL shaders** (`type: "shader"`, `kb.postEffect`) | Shaders that run on the keyboard's own picture: distortion, refraction, hologram | 13+ only |
| **GL textures** | Pictures from your `.zip` (or `data:` URIs) as `sampler2D` uniforms: textured 3D, sprites, noise and lookup-table colour effects. Up to 4 per layer, repeat / mirror / nearest / mipmaps | 8+ |
| **GL batches** | Hundreds of rects, circles and lines in one draw call | 8+ |
| **Retained groups, pan and zoom** | Send a big scene once, move the camera inside the keyboard with no script round trip | 8+ |
| **Particles and liquid** | Up to 20,000 particles, up to 3,000 liquid ones, driven by the keyboard, not the script | 8+ |
| **Own screen** | Fullscreen, landscape (`landscape`, `landscape-right`) or a floating panel for a mod: games, maps, tools | 8+ |
| **Three script engines** | Rhino (default), QuickJS (`"scriptEngine": "quickjs"`) or Lua 5.4 (`"scriptEngine": "lua"`, a `.lua` file), same `kb.*` API, may run side by side | 8+ |
| **Sensors, microphone, app, media** | Tilt, gyro, microphone level and bands, the app being typed in, now playing and player controls (each needs a permission) | 8+ |
| **Settings, themes, skins** | Plugins can add skins, themes, layouts, icons, languages, dictionaries, arrange the Settings home screen (`settings_layout`) and the keyboard toolbar (`toolbar_layout`) | 8+ |
| **Dynamic shadow, static frosted glass** | Shadows that lean with the phone tilt, a background blurred once | 8+ |

Three things people often get wrong:

- **"Shaders need Android 13+."** Only AGSL does. GLSL layers are plain OpenGL ES and work on every Android version the app installs on.
- **"Scripts are limited to 300 ms."** That is the default budget of *one script call*, and `callBudgetMs` raises it to 2000. Heavy
  drawing never runs inside the script: GL layers render on their own thread, particles, batches and retained groups are drawn by the
  keyboard, so a mod only sets parameters and the budget is almost never the limit.
- **"It is the same as the official keyboard."** This is an independent fork with its own releases. It started from the original and adds
  landscape full screen for mods, retained groups, host-side pan/zoom, GL batches, a faster GL layer, dynamic shadow, static frosted glass,
  the `media`, `settings_layout` and `toolbar_layout` permissions, installing plugins from a link, music of up to 256 MB in a `.zip` and the security notice (the README has the full list).

## Common fields

| Field | Required | Description |
|---|---|---|
| `id` | yes | 2–64 characters: `A-Z a-z 0-9 . _ -`. Importing again with the same `id` updates the plugin |
| `name` | yes | Name |
| `version`, `author`, `description` | no | Shown in the list |
| `icon` | no | An emoji, a short text, `@icon` or `data:image/png;base64,…` (see "Icons") |
| `permissions` | no | `network` — internet, `read_text` — read the typed text, `clipboard` — read the clipboard, `launch` — open links, apps and "Share", `ai` — AI requests with the user's key, `sensors` — tilt and rotation of the phone, `microphone` — volume and frequencies from the microphone, `app_info` — which app is being typed in, `screen` — show the mod's own screen (fullscreen, landscape or a floating panel), `media` — what is playing and the player controls, `settings_layout` — arrange the Settings home screen, `toolbar_layout` — arrange the keyboard toolbar |
| `config` | no | The mod's own settings — they appear in its card (see "Mod settings") |
| `script` | no | The mod's JavaScript (a string or an array of strings) |
| `scriptEngine` | no | `rhino` (default), `quickjs` or `lua` |
| `callBudgetMs` | no | How many ms one script call gets: 300 (default) … 2000. Needed by heavy mods (3D, big meshes). Time spent inside `kb.*` functions (for example parsing a mesh in `kb.overlay`) does not count toward the limit (Rhino) |
| `display` | no | The mod's own screen: `fullscreen`, `landscape`, `landscape-right` or `floating` (see "The mod's own screen") |
| `enabledByDefault` | no | `false` — a built-in plugin appears turned off |

At least one of the blocks `script`, `actions`, `shortcuts`, `symbols`, `theme`, `skin`, `settings`,
`layouts`, `variants`, `icons`, `languages` or `dictionary` must be present.

When a plugin with permissions is installed, the keyboard shows what exactly it will be able to do.

# Mods: JavaScript API

```js
/* @plugin
{ "id": "my.mod", "name": "My mod", "permissions": ["read_text"] }
*/
kb.on("word", function (e) { console.log("word typed", e.word); });
kb.addButton({ title: "Hello", icon: "👋", toolbar: true, onClick: function () { kb.commit("Hello!"); } });
```

A full example is in **Settings → Plugins and mods → Save mod example (.js)**.

## Events: `kb.on(event, function)`

| Event | Argument | What you can return |
|---|---|---|
| `key` | `{ text }` — the letter/symbol that was pressed | a string — type it instead of the symbol; `false` — cancel the press. *read_text* |
| `word` | `{ word, previous }` — a word has just been finished | — *read_text* |
| `suggest` | `{ word, previous }` — while a word is being typed | an array of strings — suggestions on the sides of the suggestion bar. *read_text* |
| `space` / `enter` / `backspace` | — | `false` — cancel the standard action |
| `start` | `kb.field()` — an input field was opened | — *read_text* |
| `finish`, `show`, `hide` | — | the field was closed / the keyboard was shown / hidden |
| `language` | language code | — |
| `clipboard` | the copied text | — *clipboard* |
| `press` | `{ label, type }` — any key was pressed (`a`, `space`, `enter`, `shift`…) | — *read_text* |
| `frame` | milliseconds since the previous frame | up to 60 times per second while the keyboard is on screen — for animations. A script whose frames are expensive is moved to 30 (or 15) by itself, so typing stays smooth |
| `selection` | `{ start, end }` — the cursor or the selection moved | — *read_text* |
| `pick` | `{ word }` — a suggestion was chosen | `false` — do not insert it. *read_text* |
| `autocorrect` | `{ word, correction }` — the space is about to correct the word (`correction` may be `null`) | a string — your own correction; `false` — do not correct. *read_text* |
| `voice` | `{ text }` — a phrase was recognized by voice | a string — insert it; `false` — do not insert. *read_text* |
| `glide` | `{ word, alternatives }` — a word was recognized from a swipe | a string — insert it; `false` — cancel. *read_text* |
| `panel` | `keyboard`, `emoji`, `clipboard`, `voice`, `plugins`, `translate`, `script_input`… | a panel was opened |
| `shift` | `off`, `single`, `caps` | Shift changed |
| `device` | `kb.device()` | screen rotation, system theme change |
| `touch` | `{ type, x, y, wx, wy, pointer }`, `type` — `down`, `move`, `up`, `cancel`; `wx`, `wy` — the same point in world coordinates of the camera (see `kb.panZoom`) | touches on the keyboard while `kb.captureTouches()` is on |
| `view` | `{ x, y, zoom }` | the camera was moved by a finger gesture (`kb.panZoom`); at most 10 times a second, and once more when the gesture ends |
| `message` | `{ from, name, data }` | a message from another mod (`kb.broadcast`) |
| `option` | `{ id, value }` | a mod setting changed, or a settings button or preset was pressed |
| `pointer` | `{ type, x, y, pointer }`, `type` — `down`, `move`, `up` | a finger on the keyboard; the keys keep working as usual. Moves arrive up to 60 times per second. *read_text*: the coordinates show what is being typed |
| `tilt` | `{ x, y, z }`, roughly from −1 to 1 | phone tilt, up to 60 times per second. *sensors* |
| `gyro` | `{ x, y, z }`, rad/s | phone rotation. *sensors* |
| `audio` | `{ level, bands }`: 0..1 and 8 bands from low to high | after `kb.audio.start()`, 20 times per second. *microphone* |
| `app` | `{ package, name, color }`, `color` — the main color of the icon | a field in another app was opened. *app_info* |
| `media` | `{ access, active, playing, title, artist, album, app, position, duration }` (ms) | the track, the player or play/pause changed. *media* |

In password fields and in incognito mode, `pointer`, `tilt`, `gyro`, `audio` and `app` are not delivered. Sensors and the microphone work
only while the keyboard is on screen; while voice input is open, the microphone belongs to it. The mod never receives the sound itself.

`kb.off(event)` removes all handlers of an event.

## Typing and reading text

| Function | Description |
|---|---|
| `kb.commit(text)` | Insert text into the field |
| `kb.deleteBefore(n)` / `kb.deleteAfter(n)` | Delete n characters before / after the cursor |
| `kb.replaceBefore(n, text)` | Delete n characters before the cursor and insert text |
| `kb.textBefore(n = 200)` / `kb.textAfter(n)` | The text before / after the cursor. *read_text* |
| `kb.selectedText()` / `kb.currentWord()` | The selection / the word before the cursor. *read_text* |
| `kb.setSelection(start, end)` | Select, or move the cursor |
| `kb.key(name)` | `enter`, `space`, `backspace`, `tab`, `left`, `right`, `up`, `down`, `home`, `end`, `select_all`, `copy`, `cut`, `paste`, `undo`, `redo`, `delete_word`, `escape`, `shift`, `language` |
| `kb.setSuggestions([...], onPick)` | Show your own words in the suggestion bar. With `onPick(word, index)` the choice goes to the mod instead of being inserted |
| `kb.setComposing(text)` / `kb.finishComposing()` | Your own "being typed" (underlined) word / commit it. Together with a `key` event that returns `false`, this gives you your own input method |
| `kb.cursor()` | `{ start, end }` — the cursor position in the field. *read_text* |
| `kb.allText()` | The whole text of the field. *read_text* |
| `kb.replaceSelection(text)` / `kb.replaceAll(text)` | Replace the selection / the whole text of the field |
| `kb.sendKey(key, { ctrl, alt, shift, meta })` | A real key: `"a"`, `"f5"`, `"tab"`, `"escape"`, `"page_up"`, `"delete"` or an Android key code. `kb.sendKey("z", { ctrl: true })` is Ctrl+Z |
| `kb.editorAction(name)` | A field action: `search`, `send`, `go`, `next`, `previous`, `done` |
| `kb.commitImage(src)` | Insert an image (`data:image/…` or a file from the .zip) if the field accepts images. Returns `true`/`false` |

## Keyboard

| Function | Description |
|---|---|
| `kb.language()` / `kb.setLanguage(id)` / `kb.languages()` | The current language / change it / the enabled languages |
| `kb.shift()` / `kb.setShift(s)` | `off`, `single`, `caps` |
| `kb.field()` | `{ package, inputType, password, multiLine, action, hint }` |
| `kb.openPanel(id)` / `kb.closePanel()` | `keyboard`, `emoji`, `clipboard`, `translate`, `gif`, `voice`, `plugins`, `plugin:<mod id>/<panel id>` |
| `kb.status(text)` / `kb.toast(text)` | A status line on the plugins panel / a popup message |
| `kb.vibrate(ms)` / `kb.hide()` | Vibrate / hide the keyboard |
| `kb.clipboard()` / `kb.copy(text)` | The clipboard history (*clipboard*) / copy |
| `kb.setIcon(slot, spec)` / `kb.resetIcon(slot)` | Replace an icon (the slots are listed in the `icons` section) |
| `kb.settings.get(key)` / `kb.settings.set(key, value)` | Any setting from the `settings` section |

## Own interface

```js
kb.addButton({ id, title, icon, toolbar: true, onClick })   // a button on the mod's tab and (toolbar) on the toolbar
kb.removeButton(id)
kb.addPanel({ id, title, icon, render })                     // your own tab on the plugins panel
kb.refresh()                                                 // redraw the panels (call render again)
kb.addCommand(name, fn)                                      // a command for a layout key { "label": "λ", "action": "name" }
```

`render()` returns an array of elements:

| Element | Fields |
|---|---|
| `{ type: "text" }` | `text`, `size`, `bold`, `color`, `center` |
| `{ type: "button" }` | `text`, `icon`, `accent`, `wide`, `onClick(value)`, `value` |
| `{ type: "row" }` / `{ type: "column" }` | `children: [...]` (a row wraps to a new line if it does not fit) |
| `{ type: "grid" }` | `items: ["😀", { text, icon, value }]`, `minWidth`, `height`, `columns`, `onClick(value)`. `columns` sets an exact number of columns (for example a calculator: `columns: 4`), `minWidth` sets the minimum cell width when wrapping |
| `{ type: "image" }` | `src` (`data:image/…` or `kb.asset("pic.png")`), `size`, `onClick` |
| `{ type: "spacer" }` | `size` |
| `{ type: "input" }` | `title`, `hint`, `value`, `onChange(text)`, `onSubmit(text)` — after a tap the keyboard types into this field |
| `{ type: "toggle" }` | `text`, `checked`, `onChange(checked)` |
| `{ type: "slider" }` | `text`, `value`, `min`, `max`, `step`, `onChange(value)` (when the finger is released) |
| `{ type: "progress" }` | `value` 0..1; without `value` — an endless bar |
| `{ type: "divider" }` | A divider |
| `{ type: "box" }` | A card: `children`, `color`, `radius`, `padding`, `border`, `onClick(value)`, `value` |
| `{ type: "tabs" }` | "Pill" tabs: `items` (as in `grid`), `selected`, `onClick(value)` |
| `{ type: "canvas" }` | A canvas: `height`, `items` (elements as in `kb.overlay`), `onTap({ x, y })` |
| `{ type: "scroll" }` | Scrolling: `children`, `horizontal`, `height` (maximum height) |

Extras: `text` also has `align` (`start`, `center`, `end`), `maxLines`, `mono`, `italic`; `button` also has `color`, `textColor`,
`onLongClick(value)`, `disabled`.

After an element is tapped, the panel is redrawn automatically.

### Text input in a mod

```js
kb.prompt({ title: "Search", hint: "What to look for?", value: "" }, function (text) {
  if (text === null) return;            // closed with the ← button
  kb.commit("Searching: " + text);
});
kb.input({ title: "Name" }).then(function (name) { kb.toast("Hello, " + name); });   // the same with a promise
kb.closePrompt();
```

While the field is open, a mod line sits above the keyboard (← close, ✓ send), and the keys type into it instead of the app.
Enter sends as well. The handler may open the next field right away — that is how multi-step dialogs are made.

## The mod's own screen: fullscreen, landscape or a floating panel

A mod can take over the screen. Which kind is chosen by the author in the manifest (`"display"`), **one kind per mod**:

| `display` | What it is |
|---|---|
| `"fullscreen"` | The keyboard window stretches over the whole screen. The app under it is not squeezed. `kb.keyboardSize()` returns the size of the whole window, so everything the mod draws with `kb.overlay` automatically becomes larger |
| `"landscape"` | **Landscape full screen.** The same as `fullscreen`, but the picture is turned 90° clockwise on a portrait screen: turn the phone counterclockwise (its top edge to the left) and you get a wide, landscape canvas. `kb.keyboardSize()` returns the **landscape** size (width is the long side), the keys, panels, overlay and touches all work in that turned space. If the phone is already in landscape, it behaves as plain `fullscreen` |
| `"landscape-right"` | The same as `landscape`, turned the other way: the picture is turned 90° counterclockwise, so turn the phone clockwise (its top edge to the right) |
| `"floating"` | The keyboard stands at the bottom as usual, and a card with the mod's panel (`kb.addPanel`) floats over the app. It can be dragged by its top strip. Touches outside the keyboard and the card go to the app |

The `"screen"` permission is required in `permissions` (on import the keyboard shows it to the owner).

```js
/* @plugin
{ "id": "my.screen", "name": "My screen", "scriptEngine": "quickjs",
  "permissions": ["screen"], "display": "landscape" }
*/
kb.on("screen", function (e) { console.log(e.open ? "opened" : "closed"); });
kb.addButton({ title: "Open", icon: "🖥", toolbar: true, onClick: function () {
  kb.screenOpen({ keys: false });      // landscape without keys; keys: true keeps them at the bottom
}});
```

| Call | What it does |
|---|---|
| `kb.screenOpen({ keys, panel, width, height, exit })` | Opens the screen in the mode from the manifest. `keys` (only for `fullscreen` and `landscape`, `true` by default) — keep the keys at the bottom or remove them completely. `panel` — the id of a panel from `kb.addPanel`: on a full screen it is drawn above the keys, for `floating` it is **required**. `width`, `height` — the size of the card in dp (160–600 × 120–700). `exit` — the corner of the ✕ button on a full screen. Only one screen can be open: a new one closes the previous one, and the mod gets a `screen` event with `open: false` |
| `kb.screenClose()` | Closes the mod's screen |
| `screen` event | `{ open, mode }`: the screen was opened or closed (including by ✕ or when the keyboard was hidden). `mode` is `fullscreen` for both `fullscreen` and the landscape kinds |

Safety rules: the ✕ button is always there and the mod cannot remove it (if the mod captures touches with `kb.captureTouches`, the ✕ of the touch layer closes the screen too).
The screen closes by itself when the keyboard is hidden. A floating mod panel does not work while the floating keyboard is on.
On a full screen the background is the normal keyboard background; games and drawing capture touches with `kb.captureTouches(true)`.
In `landscape`, `x`/`y` coordinates of overlay items and touches are in the turned (landscape) space: `x` runs along the long side, from the left edge of the picture.

## Drawing over the keyboard, and keys

The mod layer lies on top of the keys and does not get in the way of typing. Coordinates and sizes are in dp from the top-left corner of the keyboard.

| Function | Description |
|---|---|
| `kb.overlay([...])` / `kb.clearOverlay()` | Draw a set of elements (replaces the previous one) / remove it |
| `kb.background([...])` / `kb.clearBackground()` | The same, but **under the keys**: on top of the keyboard background, with the keys staying above — for live backgrounds, waves, particles. Glass keys refract the theme's normal background, not this layer |
| `kb.keyboardSize()` | `{ width, height }` |
| `kb.keys()` | All keys: `[{ label, type, x, y, w, h, cx, cy }]` (`label` is a letter or `space`, `enter`, `shift`, `backspace`, `symbols`, `globe`, `emoji`) |
| `kb.keyRect(label)` | The rectangle of one key, or `null` |
| `kb.pressKey(label, { type: true })` | The press animation; `type: false` — animation only, nothing is typed |
| `kb.colors()` | The current colors: `background`, `key`, `keyText`, `func`, `funcText`, `accent`, `accentText` |
| `kb.setLayout(id, rows)` / `kb.setLayout(id, null)` | Your own layout on the fly (the row format is the same as in `layouts`) / go back to the normal one |
| `kb.setSkin({...})` / `kb.setSkin(null)` | A skin on the fly (the format is the same as `skin`) / remove it |
| `kb.captureTouches(true/false)` | The mod layer catches touches (the `touch` event) and the keys are not pressed meanwhile — for games and finger drawing. A ✕ is always present in the corner of the keyboard: it releases the capture, and the mod gets `touch` with `type: "cancel"`. Second argument: `kb.captureTouches(true, { zone: {x, y, w, h}, exit: "topEnd" })` — `zone` (dp from the keyboard corner) catches touches only inside itself, the rest go to the keys; `exit` — the corner of the ✕ button: `topEnd`, `topStart`, `bottomEnd`, `bottomStart` |
| `kb.defineGroup(key, items)` / `kb.removeGroup(key)` | Retained group: send a set of elements **once**, then draw it by reference with `{ type: "group", key }` in `kb.overlay` / `kb.background` (see "Retained groups"). Returns `false` if the key is empty |
| `kb.view()` / `kb.view({ x, y, zoom })` | Reads / sets the camera of `world` groups (`x`, `y` in dp; screen = world × `zoom` + `x`, `y`). Returns `{ x, y, zoom }` |
| `kb.toWorld(x, y)` / `kb.toScreen(x, y)` | Converts a point between keyboard dp and world coordinates: `{ x, y }` |
| `kb.panZoom(options)` / `kb.panZoom(false)` | The keyboard itself pans and zooms the camera from finger gestures, with no script round trip (see "Pan and zoom"). Works while `kb.captureTouches` is on |
| `kb.glUniforms(id, {name: value})` | Changes a uniform of a GL layer (`type: "gl"` with an `id`) without resending the whole `kb.overlay` list: faster and with no UI redraw. A new `kb.overlay` resets these values. Returns `false` if the arguments did not fit |

`kb.overlay` elements:

| Element | Fields |
|---|---|
| `{ type: "rect" }` | `x`, `y`, `w`, `h`, `color`, `radius`, `stroke` (outline thickness; 0 — filled) |
| `{ type: "circle" }` | `x`, `y` (top-left corner), `r` or `w`/`h`, `color`, `stroke` |
| `{ type: "line" }` / `{ type: "path" }` | `points: [[x, y], …]`, `color`, `width`, `closed`, `fill` |
| `{ type: "text" }` | `x`, `y`, `text`, `size`, `color`, `bold`, `center` |
| `{ type: "pixels" }` | Pixel art: `x`, `y`, `scale` (pixel size), `rows: ["..##..", …]`, `palette: { "#": "#D97757" }`, `flipX` |
| `{ type: "image" }` | `x`, `y`, `w`, `h`, `src` (`data:image/…` or `kb.asset("pic.png")`), `flipX` |
| `{ type: "gl" }` | A GLSL layer (OpenGL ES, any Android 8+): `frag`, `vert`, `uniforms`, `mesh`, `scale`, `fps`, `depth`, `clear`, `id` (see "GLSL layers") |
| `{ type: "batch" }` | Many rects, circles and lines drawn in one OpenGL call: `shapes: [...]`, `key` (see "GL batches") |
| `{ type: "group" }` | A retained set of elements: `key`, `x`, `y`, `scale`, `world` (see "Retained groups") |

All elements have `alpha` (0..1) and `rotation` (degrees).

Example: a character walks from key to key and "presses" them.

```js
/* @plugin
{ "id": "my.walker", "name": "Pet", "icon": "🐾" }
*/
var SPRITE = ["..####..", ".######.", "##.##.##", "########", ".#.##.#.", "#.#..#.#"];
var x = 20, y = 20, target = null, flip = false;
kb.on("frame", function (dt) {
  if (!target) {
    var keys = kb.keys().filter(function (k) { return k.type === "character"; });
    if (!keys.length) return;
    target = keys[Math.floor(Math.random() * keys.length)];
  }
  var dx = target.cx - x, dy = target.cy - 12 - y, d = Math.sqrt(dx * dx + dy * dy);
  var step = 0.12 * dt;                       // speed: 120 dp per second
  if (d <= step) {
    kb.pressKey(target.label, { type: false }); // animation only — nothing is typed into the field
    target = null;
  } else { x += dx / d * step; y += dy / d * step; flip = dx < 0; }
  kb.overlay([{ type: "pixels", x: x - 12, y: y - 9, scale: 3, rows: SPRITE, palette: { "#": "#D97757" }, flipX: flip }]);
});
```

## Effects: particles, shaders, key tilt

The script runs in an interpreter and cannot count thousands of objects per frame, so the keyboard does the heavy work itself:
the mod only sets parameters. `kb.supports(name)` checks a capability: `gl`, `batch`, `groups`, `panZoom`, `shader`, `postEffect`, `labelBlur`, `particles`, `keyEffects`, `pointer`, `sensors`, `microphone`, `media`, `mediaInfo`, `imageSettings`.

### Particles

Up to 20,000 particles (liquid — up to 3,000), up to 8 systems per mod. They bounce off the keys and react to the finger and to tilt on their own,
without a single script call, so no permissions are needed for this.

| Function | Description |
|---|---|
| `kb.particles.set(name, settings)` | Create a system or change its settings on the fly |
| `kb.particles.emit(name, { count, x, y, w, h, speed, angle, spread })` | Throw particles out of a point or a rectangle; angles are in degrees, 0 — to the right |
| `kb.particles.clear(name)` / `remove(name)` / `count(name)` | Remove the particles / the whole system / find out how many there are |

| Setting | Description |
|---|---|
| `max`, `size`, `color` or `colors` (up to 8) | How many, size in dp, colors |
| `layer` | `background` (under the keys, default) or `overlay` |
| `gravity` | `[x, y]` or `y`, dp/s² |
| `drag`, `bounce`, `life`, `fade` | Drag, bounciness 0..1, lifetime in seconds (0 — forever), fading |
| `bounds` | The keyboard edges: `bounce`, `kill`, `wrap` |
| `collideKeys` | Bounce off the keys |
| `tilt` | Gravity from the phone tilt, dp/s² |
| `touchForce`, `touchRadius` | The finger pushes (above 0) or pulls (below 0) |
| `emitOnTouch` | How many particles are born under the finger on every move |
| `rate`, `rateX`, `rateY`, `rateW`, `rateH`, `speed` | A constant stream from a rectangle, particles per second |
| `liquid`, `liquidRadius`, `stiffness`, `viscosity` | Liquid: the particles push each other apart and flow together. It is a fast approximation, not a solution of the Navier–Stokes equations |

```js
kb.particles.set("water", { max: 1500, liquid: true, size: 7, color: "#664FC3F7", gravity: 500, tilt: 900,
  collideKeys: true, touchForce: 6000, bounce: 0.2 });
kb.particles.emit("water", { count: 1500, x: 0, y: 0, w: 360, h: 60 });
```

### Shaders (AGSL, Android 13+)

For a shader that must work on every phone use a GLSL layer (next section). The language here is AGSL: almost GLSL, but the entry point is `half4 main(float2 fragCoord)` and the types are `float2`, `half4`. On older Android versions the shader is
not drawn. A compile error goes to the mod's log, and the text can be obtained with `kb.shaderError(src)`.

A `kb.overlay` and `kb.background` element: `{ type: "shader", src, x, y, w, h, uniforms: { ... }, alpha, rotation }`.
Without `w` and `h` it covers the whole keyboard. It animates by itself, so you do not need to call `kb.overlay` every frame.
`kb.postEffect({ src, uniforms })` applies a shader to the whole picture of the keyboard: distortion, hologram, refraction.
The picture arrives as `uniform shader content`. `kb.postEffect(null)` removes the effect. The distortion does not shift where keys are pressed.

`uniforms` values: a number, an array of numbers (`float2`..`float4`) or a color `"#RRGGBB"` (into `float4`).
These are filled in by themselves if declared: `float iTime`, `float2 iResolution` (px), `float3 iTouch` (x, y in px, pressed 0/1),
`float2 iTilt`, `float iAudio` (only for a mod with *microphone*, when it is on).

```js
kb.postEffect({ src: `
  uniform shader content; uniform float iTime;
  half4 main(float2 p) {
    p.x += sin(p.y * 0.04 + iTime * 3.0) * 4.0;
    return content.eval(p);
  }` });
```

### GLSL layers (`type: "gl"`, all Android versions)

A `gl` element in `kb.overlay` or `kb.background` draws your own OpenGL ES shaders. It works on **Android 8+**, unlike AGSL. Each layer
has its own render thread and OpenGL context, so a slow shader or a busy script does not slow down typing. Check with `kb.supports("gl")`.

| Field | Description |
|---|---|
| `frag` | **Required** (unless the layer has a `scene`, see **3D scenes** below). The fragment shader source (up to 64,000 characters). Starting it with `#version 300 es` selects GLSL ES 3.00 (needs a phone with OpenGL ES 3.0; the keyboard says so in the log if not). Otherwise it is GLSL ES 1.00 and `precision mediump float;` is added for you if you did not write any |
| `vert` | Optional vertex shader. Without it a full-screen quad is drawn with the attribute `aPos` (−1..1) and the varying `vUv` (0..1), which is all a fragment-only effect needs. In an ES 3.00 shader the names are `in vec2 aPos` / `out vec2 vUv` |
| `mesh` | Optional geometry for your own vertex shader (see below) |
| `uniforms` | `{ name: value }`: a number, a boolean, an array of numbers (`vec2`..`vec4`, or a whole `float[]`) or a color `"#RRGGBB"` / `"#AARRGGBB"` (into `vec4`) |
| `x`, `y`, `w`, `h` | Where the layer is, in dp. Without `w` and `h` it covers the whole drawing area |
| `scale` | Resolution of the render buffer, 0.1..1 (default 1). `0.5` draws a quarter of the pixels, which is cheap for soft effects |
| `fps` | 1..60 (default 30) |
| `depth` | `true` asks for a depth buffer (when the phone has none, the layer still draws and the log says so) |
| `clear` | The color the buffer is cleared to before each frame (default transparent) |
| `id` | A name for the layer. With it, `kb.glUniforms(id, { name: value })` changes uniforms without resending the list |
| `feedback` | `true` turns on render-to-texture: the layer is drawn into two off-screen buffers that swap every frame, and a shader that declares `uniform sampler2D iPrev;` reads the **previous frame** (`texture2D(iPrev, vUv)`, or `texture(iPrev, vUv)` in ES 3). This unlocks motion blur, VHS trails, bloom, mirrors and feedback fractals. Output is premultiplied alpha like any layer. The first frame is transparent. Costs two textures of the buffer size |
| `textures` | `{ name: source }`: up to 4 pictures the shader reads as `uniform sampler2D name;` (see **Textures in GLSL layers** below). A source is a file name from the plugin's `.zip`, a `data:image/...;base64,` URI or the value of an `image` setting (`kb.config().photo`); `{ src, wrap, filter, mipmap }` sets how it is sampled |
| `autoQuality` | The host lowers the resolution when frames are slow (step down 15% after ~10 slow frames, back up slowly when there is room) and lowers fps and resolution when the phone heats up (Android 10+: `THERMAL_STATUS_MODERATE` = max 30 fps and 85% resolution, `SEVERE` = 20 fps and 70%, `CRITICAL` = 12 fps and 50%). Default `true`; `false` draws exactly what you asked for |
| `minScale` | The lowest resolution factor the governor may use for this layer, 0.1..1 (default 0.4). It multiplies `scale` |
| `scene` | `{ models, camera, lights, objects, ... }`: the built-in 3D engine draws lit 3D objects and models after the shader, in the same depth buffer (see **3D scenes** below). Check with `kb.supports("scene3d")` |
| `alpha`, `rotation` | Like any element |

These uniforms are filled in by the keyboard if the shader declares them: `float iTime` (seconds), `float iFrame`, `vec3 iResolution`
(width and height of the buffer in px, then 1), `float iDensity`, `vec3 iTouch` (x and y in buffer px from the layer's top-left corner, and 1
while a finger is down; it follows the finger by itself, no `pointer` handler needed), `vec3 iTilt` (from the motion sensor, only while the
keyboard is open) and `float iAudio` (0..1, only for a mod with *microphone*, after `kb.audio.start()`). All other uniforms come from `uniforms` or
`kb.glUniforms`.

**Textures in GLSL layers.** `textures` gives the shader pictures to read, so a mesh can wear a brick wall, a sprite sheet can be cut
in the shader, and a noise or lookup image can drive an effect. Check with `kb.supports("glTextures")`.

```js
// brick.png lives in the .zip mod; the shader reads it as iTex0
kb.overlay([{ type: "gl", vert: V, frag: F, mesh: cube, depth: true,
  textures: { iTex0: { src: "brick.png", wrap: "repeat", filter: "nearest", mipmap: false } } }]);
// in F:   uniform sampler2D iTex0;   uniform vec2 iTex0Size;   ...   texture2D(iTex0, uv)    (texture(iTex0, uv) in ES 3)
```

| Part | What it does |
|---|---|
| name (`iTex0`, `uNoise`, ...) | Letters, digits and `_`, up to 32 characters, not starting with a digit, and not a name the keyboard fills in itself (`iPrev`, `iTime`, `iFrame`, `iResolution`, `iDensity`, `iTouch`, `iTilt`, `iAudio`). A bad entry is skipped |
| source | A file of the plugin's `.zip` (`"brick.png"`, `"img/brick.png"`; a leading `./` is fine, `..` is refused) or a `data:image/...;base64,...` URI. `kb.asset("brick.png")` also works, but a plain file name is cheaper: the file is read once on the render thread instead of being turned into text and sent again with every `kb.overlay`. An **empty string** is an empty slot (see below) |
| `wrap` | `"clamp"` (default), `"repeat"` or `"mirror"`: what happens for uv outside 0..1 |
| `filter` | `"linear"` (default, smooth) or `"nearest"` (hard pixels, for pixel art) |
| `mipmap` | `true` builds smaller copies for a smooth look when the picture is drawn small (a 3D floor far away) |
| `uniform vec2 <name>Size;` | Optional. Filled with the size of the uploaded picture in pixels (`iTex0Size` for `iTex0`) |

- Up to **4 textures** per layer; the same picture can be listed twice under other names or settings (it becomes two textures).
- **Formats:** whatever Android decodes: PNG, JPEG and WebP. SVG is not supported. A file may be up to 16 MB.
- **Size:** the longest side is limited to 2048 px (or the phone's own limit when that is smaller); bigger pictures are scaled down when they are
  uploaded. On a phone with only OpenGL ES 2.0, `repeat`, `mirror` and `mipmap` need power-of-two sizes, so the picture is scaled to
  the next power of two (never above the limit); use `<name>Size` if your maths needs the real size. OpenGL ES 3 phones keep the size.
- **Orientation and colour:** pixel (0, 0) is the **bottom-left** corner, the same as `vUv`, so `texture2D(iTex0, vUv)` shows the picture upright.
  Colours are **premultiplied by alpha**, like the layer's own output, so `gl_FragColor = texture2D(iTex0, uv)` is already correct.
- **Loading:** a picture is decoded and uploaded on the layer's render thread the first time it is drawn (a large picture can take a few
  tens of milliseconds once, and never blocks typing). It stays while the layer lists it, is replaced when the file changes (the plugin is
  updated) and is deleted when the layer stops listing it.
- **Errors** go to the mod's **Script log** and `kb.glError(fragSource)`: a file that is not in the `.zip`, something that is not a picture,
  or a `sampler2D` in the shader without a `textures` entry. A picture that cannot be loaded is drawn as one magenta pixel, so the mistake is visible.
- `tools/plugin-test/run.mjs` checks the same things before you install: the files exist in the plugin and every `sampler2D` has an entry.
- A complete example (a textured cube) is [`gl_textures.js`](app/src/main/assets/examples/gl_textures.js).

**Pictures chosen by the user.** A setting of type `image` lets the person using your mod pick a picture from the gallery (a photo on the cube,
their own sprite, a custom noise). Check with `kb.supports("imageSettings")`.

```json
"config": [
  { "id": "photo", "title": "Photo on the cube", "type": "image", "description": "Choose a picture from the gallery" }
]
```

```js
// the value of the setting is the texture source: pass it as it is
kb.overlay([{ type: "gl", vert: V, frag: F, mesh: cube, depth: true,
  textures: { iTex0: { src: kb.config().photo || "", mipmap: true } } }]);
kb.on("option", draw);   // the person chose or removed the picture: draw again
```

- The settings card shows a preview, a **Choose picture** button and a **Remove picture** button.
- `kb.config().photo` is a short text like `"user:photo:1732450000000"` while a picture is chosen and `""` while there is none. The text changes with every new
  picture, so the old texture is replaced. It is **not** a file name or a `data:` URI: do not change it, and do not use it with `kb.asset`.
- **Empty slot:** `""` as a texture source is allowed. The shader gets one transparent pixel (`uniform vec2 iTex0Size;` is `1 x 1`), nothing is
  logged, so `if (iTex0Size.x < 2.0)` is the way to draw a placeholder until a picture is chosen.
- **Privacy:** the picture is copied into the app's own storage (longest side at most 2048 px, turned upright by its EXIF tag, PNG when it has
  transparency, JPEG otherwise). The mod needs no storage permission and can only use pictures chosen in **its own** `image` settings, never
  another mod's picture and never other files of the phone. The picture is deleted with the mod.
- An `image` setting can be used only as a GL texture. `kb.http`, `kb.commitImage` and `kb.asset` do not take it.
- `tools/plugin-test/run.mjs` runs the mod once with every image setting empty and once with a picture chosen.
- A complete example (a cube with your photo) is [`gl_photo.js`](app/src/main/assets/examples/gl_photo.js).

```js
// A live, full-keyboard plasma under the keys. Works on any Android 8+.
kb.background([{ type: "gl", id: "plasma", scale: 0.5, fps: 30, alpha: 0.6, uniforms: { tint: "#7C4DFF" }, frag: `
  varying vec2 vUv;
  uniform float iTime;
  uniform vec4 tint;
  void main() {
    float v = sin(vUv.x * 10.0 + iTime) + sin(vUv.y * 8.0 - iTime * 1.3);
    vec3 c = 0.5 + 0.5 * sin(v + vec3(0.0, 2.0, 4.0));
    gl_FragColor = vec4(c * tint.rgb, 1.0);
  }` }]);
kb.glUniforms("plasma", { tint: "#00E5FF" });   // later: no redraw of the whole list
```

**Meshes and vertex shaders (3D).** `mesh` gives your vertex shader its data:

```js
mesh: {
  mode: "triangles",                      // triangles (default), strip, fan, lines, lineStrip, points
  attribs: {                              // up to 8 attributes; name = the `attribute` in your vertex shader
    aPos: { size: 3, data: [ -0.5,-0.5,0,   0.5,-0.5,0,   0,0.5,0 ] },
    aCol: { size: 3, data: [ 1,0,0,   0,1,0,   0,0,1 ] }
  },
  indices: [0, 1, 2],                     // optional
  key: "tri"                              // optional: the keyboard keeps the mesh; later send { key: "tri" } with no attribs
}
```

- A mesh may hold up to 1,000,000 numbers and 1,000,000 indices. Indices above 65535 need OpenGL ES 3 or the `OES_element_index_uint`
  extension (almost every phone has one of the two).
- The host keeps up to 32 meshes by `key`. Sending the same mesh again does not upload it to the GPU a second time.
- Up to 4 GL layers per `kb.overlay` / `kb.background` list. `gl` and `batch` elements are not drawn inside groups or `canvas` panels.
- A shader that does not read `iTime`, `iFrame`, `iTouch`, `iTilt` or `iAudio` is drawn once and then only when something changes. A layer with `feedback: true` is always redrawn at its fps.
- **Instancing (OpenGL ES 3.0, `#version 300 es`)**: give the mesh `instances: N` and mark per-copy attributes with `divisor: 1`
  (`aOff: { size: 3, divisor: 1, data: [...] }`, N groups of numbers). One draw call then paints N copies and `gl_InstanceID` counts them.
  The plain vertex attributes describe one copy. On a phone without ES 3 only one copy is drawn and the log says so.
- **Shader cache**: on OpenGL ES 3 phones the compiled program is saved to the app cache and loaded on the next launch, so heavy shaders no
  longer freeze the first frames. Updating the GPU driver or the system invalidates it by itself.
- **`kb.glDebug(true)`** shows one line per GL layer over the keyboard: fps, frame time, resolution factor, heat level and the last shader error
  (`kb.glDebug(false)` hides it). The same numbers (plus shader cache hits) are in the mod's diagnostics.
- `kb.supports("glFeedback")`, `kb.supports("glInstancing")`, `kb.supports("glDebug")`, `kb.supports("glQuality")`, `kb.supports("glTextures")`, `kb.supports("imageSettings")` and `kb.supports("scene3d")` are `true` in versions that have these.

If the shader fails to compile or link, the reason goes to the mod's **Script log** and `kb.glError(fragSource)` returns it (or `null`).
Reading the same uniform name in both stages with different precision is a common link error: declare it `highp` in both, or only in one stage.

### 3D scenes (`scene`, built-in 3D engine)

A `gl` element with a `scene` is drawn by the keyboard's own 3D engine: models, a camera, lights, materials and fog, with no shader code. The
objects are drawn **after** the layer's shader into the **same framebuffer and depth buffer**, so a mesh and a raymarcher or raycaster can share
depth (see below). A layer with a `scene` and no `frag` is a pure 3D layer (nothing is drawn behind the objects). The scene turns the depth
buffer on by itself (`depth: false` turns it off). Check with `kb.supports("scene3d")`. Complete example: [`gl_scene.js`](app/src/main/assets/examples/gl_scene.js).

```js
kb.overlay([{ type: "gl", id: "demo", fps: 30,
  scene: {
    models: { ship: { src: "ship.glb", fit: 1.5, center: "base" } },     // files of the .zip; "cube" etc. need no file
    camera: { pos: [0, 1.8, 4.5], target: [0, 0.4, 0], fov: 55 },
    ambient: "#2a2e40",
    fog: { color: "#0b0b14", density: 0.07 },
    lights: [{ type: "sun", dir: [-0.4, -1, -0.3], intensity: 0.8 }, { type: "point", pos: [1, 2, 1], color: "#ff6a3d", range: 7 }],
    objects: [
      { id: "floor", model: "plane", scale: [12, 1, 12], material: { color: "#2a2f3a" } },
      { id: "s", model: "ship", pos: [0, 0.5, 0], rot: [0, 30, 0] }
    ]
  } }]);
kb.scene("demo", { camera: { yaw: 40 }, objects: { s: { pos: [1, 0.5, 0] } } });   // later: only what the patch names changes
```

The world is right-handed with **+Y up**. A camera with yaw 0 and pitch 0 looks toward **−Z**; positive yaw turns it to the left. Angles are in degrees.
An object's rotation `[x, y, z]` is applied as Y · X · Z (yaw, pitch, roll). Sizes are in your own units.

| Scene field | Description |
|---|---|
| `models` | `{ name: "cube" }` or `{ name: { src, rotate, center, fit, scale } }`, up to 16. A source is a built-in shape (`cube`, `plane`, `sphere`, `cylinder`, `quad`) or a file of the `.zip`: **`.obj`** (with its `.mtl`) or **`.glb` / `.gltf`** (with its `.bin` and pictures). Options: `rotate` `[x, y, z]` degrees applied to the model once (for models that are Z-up), `center` (`"center"` or `"base"`: move the model so its middle, or the middle of its underside, is at the origin), `fit` (scale the model so its largest side is this long) and `scale` (a factor, after `fit`). Up to 600,000 vertices and 16 MB per file |
| `camera` | `pos` `[x, y, z]`, `yaw`, `pitch` (−89.9..89.9), `roll`, `fov` (5..170, vertical, default 60), `near` (0.05), `far` (200). `target: [x, y, z]` makes the camera look at that point (then `yaw` and `pitch` are ignored) |
| `ambient` | The light that is everywhere: a color (default `"#333333"`) |
| `lights` | Up to 8. `{ type: "sun", dir, color, intensity }` (a directional light; `dir` is the way the rays travel) or `{ type: "point", pos, color, intensity, range }` (`range` 0 = fades with distance squared; otherwise reaches zero at that distance) |
| `fog` | `{ color, density }` (exponential; `null` = none) |
| `objects` | A list of up to 256: `{ id, model, pos, rot, scale, visible, material }`. `scale` is `[x, y, z]` or one number. Without an `id` an object is called `o0`, `o1`, ... |

`material` changes how an object looks; whatever it does not name comes from the model (an `.mtl` or glTF material, its color and picture):
`color`, `emissive` (adds light-independent color), `specular` (0..4, the strength of the highlight) and `shininess` (1..512), `texture` (a picture of the
`.zip`, a `data:image/...;base64,` URI or the value of an `image` setting; `""` removes the model's picture), `unlit` (no lighting at all, only color and
picture), `doubleSided` and `alphaCut` (0..1: pixels with less alpha than this are not drawn, for leaves and fences). Lighting is per pixel (Blinn-Phong).

**`kb.scene(id, patch)`** changes the scene of the `gl` layer with that `id` and returns `true` (`false` for an unknown layer). The patch holds only what changes:
`camera` fields are merged one by one, `ambient`, `fog` and `lights` are replaced, and `objects` is either a map `id → fields` (an unknown id with a `model` adds an
object, `null` removes one) or a list that replaces all objects. A new `kb.overlay` starts the scene again from its description. Moving a camera or an
object this way does not rebuild anything, so it is cheap; call it from a timer or an event. Lua mods call `kb.scene` too.

**Sharing depth with a shader.** The layer's own `frag` runs first. If it declares any of these uniforms the keyboard fills them with the scene's
camera: `mat4 uViewProj`, `mat4 uInvViewProj`, `vec3 uCamPos`, `vec3 uCamForward`, `vec3 uCamRight`, `vec3 uCamUp` and `vec4 uCamParams` (`tan(fov/2)`, aspect, near,
far). A raymarcher can then build the same ray as the engine's camera and write its hit distance to `gl_FragDepth` (GLSL ES 3.00: `#version 300 es`); with the depth
value of a point `z` units in front of the camera `((far + near) / (far - near) - 2 * far * near / ((far - near) * z)) * 0.5 + 0.5`, objects hide behind walls and
show in front of them. Errors (a model that did not load, a missing picture or file) go to the mod's **Script log** once and to `kb.glError`.

Not in this version: skinned animation, shadows, bloom and other post effects, normal maps. Models are static; move or rotate objects from the mod.

### Installing and updating from a link
In the plugin settings, **Install from a link** takes an https address of a plugin file (`.js`, `.json` or a `.zip` with media). A GitHub page
address (`github.com/user/repo/blob/main/x.js`) is turned into the raw file address for you. The app downloads the file, reads its `id` and
`version`, and installs it. If a plugin with the same `id` is already installed, it is replaced only when the link has a **newer version**
(`1.10` is newer than `1.9`). The link is remembered, and **Check linked plugins for updates** looks at every remembered link and installs the
newer versions. Only install plugins from sources you trust: a plugin runs code in your keyboard.

### Key tilt and labels

`kb.keyEffects({ selector: style })` sets how the keys look, `kb.keyEffects(null)` removes it. A selector is a label (`a`, `space`, `enter`),
`letter`, `func` or `*`; the most specific one wins. It is fine to call it every frame.
`kb.keyboardEffect(style)` tilts the whole keyboard, `null` removes it.

| Field | Description |
|---|---|
| `rotationX`, `rotationY`, `rotationZ` | Rotation in degrees; X and Y up to ±80 |
| `scale`, `x`, `y`, `alpha` | Scale, shift in dp, transparency |
| `camera` | Camera distance: smaller means stronger perspective (default 8) |
| `labelAlpha`, `labelBlur`, `labelX`, `labelY`, `labelScale` | The label only (letters, not icons). `labelBlur` needs Android 12+, the rest works everywhere |

```js
// parallax: the grid tilts after the phone (needs "sensors")
kb.on("tilt", function (t) { kb.keyboardEffect({ rotationY: t.x * 12, rotationX: -t.y * 12, camera: 20 }); });
// a letter "evaporating" after a press: labelAlpha, labelBlur and labelY change smoothly in the frame event
```

Keys are pressed where they are drawn; with a strong tilt of the whole keyboard this is noticeable.

### Sensors, microphone, app

| Function | Description |
|---|---|
| `kb.tilt()` | `{ x, y, z }`. *sensors* |
| `kb.audio.start()` / `stop()` / `level()` | Turn the sound on and off (`start` returns `false` if the keyboard has no microphone access) / volume 0..1. *microphone* |
| `kb.appInfo()` | `{ package, name, color }` of the current app. *app_info* |

Typing speed is counted in a plain script: measure the time between `press` events.

## Data, network, timers

| Function | Description |
|---|---|
| `kb.storage.get(key, def)` / `set(key, value)` / `remove(key)` / `keys()` / `clear()` | The mod's storage (JSON, survives a restart) |
| `kb.asset(path)` | A file from the .zip: an image → a `data:` URI, anything else → text. A big file can be read this way only up to 16 MB; larger ones are for `kb.playAudio` |
| `kb.http({ url, method, headers, body }, function (err, res) {})` | `https://` only; `res = { status, ok, text, json }`. *network* |
| `kb.translate(text, to, function (err, result) {})` | Translation with automatic language detection. *network* |
| `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval` | Timers (up to 64 at the same time) |
| `console.log`, `kb.log` | Write to the mod's log |
| `kb.http({ …, responseType: "base64" }, cb)` | The response as a file: `res.base64` (up to 10 MB) — for example, an image for `kb.commitImage` |
| `kb.files.write(name, text)` / `append` / `read(name)` / `writeBase64` / `readBase64` / `exists` / `delete` / `list()` | The mod's files: its own folder, names `A-Z a-z 0-9 . _ -`, up to 32 MB in total |

`kb.http` is limited to 60 requests per minute.

## Promises

The sandbox has `Promise` and `.then()`; `async`/`await` and default parameters are not supported by the engine. Ready-made wrappers:

| Function | What it returns |
|---|---|
| `kb.fetch(url, { method, headers, body, responseType })` | A promise with the `kb.http` response (*network*) |
| `kb.ask(prompt, text)` | A promise with the AI answer (*ai*) |
| `kb.translateAsync(text, to)` | A promise with the translation (*network*) |
| `kb.input({ title, hint, value })` | A promise with the entered text (`null` — closed) |
| `kb.sleep(ms)` | A promise that resolves after ms |

```js
kb.fetch("https://api.github.com/zen").then(function (r) { kb.commit(r.text); }, function (e) { kb.toast(e.message); });
```

## Mods in Lua

A mod can be written in Lua 5.4 instead of JavaScript. Save it as a `.lua` file and start it with the manifest in a Lua long comment:

```lua
--[=[ @plugin
{ "id": "my.lua.mod", "name": "My Lua mod", "permissions": ["read_text"] }
]=]
kb.on("word", function(e) kb.toast("a word was typed") end)
```

The comment is the same plugin JSON as in a `.js` mod and the engine is set by the comment itself. Inside a `.zip` use `"scriptEngine": "lua"` and `"main": "main.lua"`.
The example `hello.lua` is in `app/src/main/assets/examples/`.

The `kb.*` functions, events, permissions and time limits are the same as for JavaScript mods. What is different:

- **Tables** are both objects and lists. A table with keys `1..n` and nothing else becomes a list, any other table an object (an empty table is a list). Lists start at 1.
- **`nil`** is both `null` and `undefined`. Callbacks are plain functions: `kb.on("word", function(e) ... end)`.
- **No promises.** `kb.fetch`, `kb.ask`, `kb.translateAsync`, `kb.input`, `kb.sleep` and `kb.saves` exist only for JavaScript. Use the callback forms: `kb.http(opts, function(err, res) ... end)`, `kb.ai`, `kb.translate`, `kb.prompt`, `setTimeout`.
- **`kb.json.encode(value)` and `kb.json.decode(text)`** for JSON. `kb.storage.get(key, fallback)` and `kb.storage.set(key, value)` keep Lua values.
- **`print(...)`** and `console.log(...)` write to the Script log.
- **`require("lib.maze")`** (or `require("lib/maze.lua")`) loads another `.lua` file from the mod's `.zip`; the result is cached.
- **Sandbox.** The standard libraries `base`, `coroutine`, `table`, `string`, `utf8` and `math` are there. `io`, `os`, `debug`, `package`, `dofile`, `loadfile`, `collectgarbage` and `string.dump` are removed, and `load` reads text only. A script gets 64 MB of memory, and a script that runs past its time limit is stopped even if it wraps the loop in `pcall`.
- Lua mods are not covered by the `tools/plugin-test` check (it runs JavaScript in Node).

## Sound, vibration, voice

| Function | Description |
|---|---|
| `kb.tone(hz, ms, { volume, wave })` | A synthesized sound; `wave`: `sine`, `square`, `saw`, `triangle`, `noise` |
| `kb.playAudio(src, { volume, loop })` / `kb.stopAudio(id)` | A sound from `data:audio/…` (up to 8 MB) or a file of the .zip (a big file plays straight from disk, so music is limited only by the 256 MB of the archive); up to 8 sounds at once; without `id` — stop all |
| `kb.sound(name, volume)` | A system key sound: `standard`, `space`, `delete`, `enter`, `click` |
| `kb.haptic(kind)` / `kb.haptic([ms, ms, …])` | Vibration: `tick`, `click`, `heavy`, `double` or your own pattern (pause, vibration, pause…; up to 6 s) |
| `kb.speak(text, { lang, rate, pitch })` / `kb.stopSpeaking()` | Read aloud with the system speech synthesizer |

## AI, links, apps

| Function | Description |
|---|---|
| `kb.ai({ prompt, text }, function (err, result) {})` | A request to the AI configured by the user (Gemini, Claude, OpenAI, Groq); up to 10 per minute. *ai* |
| `kb.openUrl(url)` | Open `https:`, `http:`, `mailto:`, `tel:`, `sms:`, `geo:`, `market:`, `tg:`, `whatsapp:`. *launch* |
| `kb.share(text)` | The "Share" sheet. *launch* |
| `kb.launchApp(package)` | Launch an app by its package name. *launch* |

## Dictionary, emoji, device, other mods

| Function | Description |
|---|---|
| `kb.dictionary.predict(word, previous, lang)` | The keyboard's suggestions for a word |
| `kb.dictionary.correct(word, lang)` | The autocorrection of a word, or `null` |
| `kb.dictionary.learn(word, lang)` / `forget(word, lang)` | Learn / forget a word |
| `kb.dictionary.nextLetters(prefix, lang)` | Likely next letters: `{ "a": 0.3, … }` |
| `kb.emoji.search(query, limit)` / `kb.emoji.recent()` | Emoji search (`[{ emoji, name }]`) / recent emoji |
| `kb.device()` | `{ sdk, android, manufacturer, model, dark, landscape, screenWidth, screenHeight, locale, battery, charging, powerSave, appVersion, time, timezone }` |
| `kb.mods()` | The enabled mods: `[{ id, name, version }]` |
| `kb.broadcast(name, data)` | A message to other mods (the `message` event) |
| `kb.util.sha256(text)` / `sha1` / `md5` | A hash as a hex string |
| `kb.util.base64Encode` / `base64Decode` / `urlEncode` / `urlDecode` / `uuid()` / `now()` | Useful helpers |
| `kb.util.formatDate(ms, "dd.MM.yyyy HH:mm")` | A date in your own format |
| `kb.util.transform(name, text, { prefix, suffix, pattern, replacement })` | Any transform from `transform`: `upper`, `translit`, `layout_swap`, `calc`, `bold`… |
| `atob` / `btoa` | Base64, as in a browser |

## Mod settings


```json
"config": [
  { "id": "greeting", "title": "Greeting", "type": "text", "default": "Hello" },
  { "id": "loud", "title": "With sound", "type": "toggle", "default": true },
  { "id": "speed", "title": "Speed", "type": "slider", "min": 1, "max": 10, "step": 1, "default": 5 },
  { "id": "style", "title": "Style", "type": "choice", "default": "cat",
    "choices": [{ "id": "cat", "title": "Cat" }, { "id": "dog", "title": "Dog" }],
    "description": "Who runs over the keys" }
]
```

The settings are shown in the mod's card (**Settings → Plugins and mods**). Up to 300 items in total. In the script use `kb.config("loud")`,
`kb.config()` (all at once), `kb.setConfig("loud", false)`, `kb.setConfigs({ loud: false, speed: 3 })`; a change arrives
as an `option` event. The card immediately shows values set from the script. A value is converted to the type of the setting: `1` in
a toggle means "on". Setting the same value again produces no event.

| `type` | What it is |
|---|---|
| `toggle`, `slider`, `choice`, `text` | A switch, a slider, a choice, a line of text |
| `textarea` | A multi-line field, for example for pasting a preset: up to 256 KB, optional `hint` |
| `color` | A `#RRGGBB` color with a swatch |
| `image` | A picture the user chooses from the gallery (see **Pictures chosen by the user** below). The value is a short text for `textures`, not a file name |
| `preset` | A button: sets all the settings from `values` at once. Works even if the mod is not running yet |
| `button` | A button: the mod receives an `option` event with its `id` |
| `info` | Plain text (`title`) |
| `tab` | A new tab: everything after it goes into it, no `id` needed |
| `section` | A collapsible section: `collapsed: true` — collapsed at first, no `id` needed |

Any setting can have `showIf`: show it only if another one is on (`"fire"`), off (`"!fire"`) or equal to
a value (`"style=cat"`).

```json
"config": [
  { "type": "tab", "title": "Fire" },
  { "id": "fire", "title": "Fire", "type": "toggle" },
  { "type": "section", "title": "Fine tuning", "collapsed": true },
  { "id": "heat", "title": "Heat", "type": "slider", "min": 0, "max": 10, "default": 5, "showIf": "fire" },
  { "type": "tab", "title": "Presets" },
  { "id": "dev", "type": "preset", "title": "Developer", "values": { "fire": true, "heat": 10 } },
  { "id": "import", "title": "Paste a preset", "type": "textarea", "hint": "{ \"fire\": true }" }
]
```

For the `import` field to work as a preset, parse the text in the mod:
`kb.on("option", function (e) { if (e.id === "import") kb.setConfigs(JSON.parse(e.value)); });`

The API version is `kb.apiVersion` (currently 4).

## Icons

Everywhere there is an `icon` (a plugin, an action, a button, a panel, an element) you can use:
- an emoji or a short text: `"🚀"`, `"Aa"`;
- `"@name"` — a built-in icon: `@layout_swap`, `@globe`, `@backspace`, `@enter`, `@shift`, `@grid`, `@translate`, `@search`,
  `@star`, `@heart`, `@settings`, `@mic`, `@clipboard`, `@copy`, `@cut`, `@undo`, `@redo`, `@calc`, `@text`, `@code`, `@link`,
  `@time`, `@date`, `@music`, `@image`, `@bolt`, `@magic`, `@note`, `@list`, `@check`, `@close`, `@refresh`, `@send`, `@case`,
  `@select_all`, `@lock`, `@emoji`, `@gif`, `@games`, `@dice`, `@palette`, `@extension`, `@keyboard`, `@language`, `@add`,
  `@delete`, `@edit`, `@share`, `@home`, `@person`, `@mail`;
- an image `"data:image/png;base64,…"` (add `#tint` at the end to recolor it with the theme color).

## icons: replacing keyboard icons

```json
"icons": { "mic": "🎙️", "globe": "🌍", "backspace": "icons/back.png", "emoji_cat_0": "🙂" }
```

Slots: `menu`, `back`, `mic`, `plugins`, `translate`, `gif`, `clipboard`, `emoji`, `one_handed`, `incognito`, `settings`,
`hide`, `layout_swap`, `globe`, `emoji_key`, `shift`, `caps`, `backspace`, `enter`, `go`, `send`, `search`, `done`,
`emoji_recent`, `emoji_cat_0` … `emoji_cat_6`. The value is an emoji/text, `data:image/…` or a path to an image in the .zip.
Icons chosen by the user in **Settings → Icons and emoji** take priority over icons from plugins.

## languages: your own language

```json
"languages": [{
  "id": "cv", "name": "Чӑвашла", "title": "Chuvash", "label": "CV", "locale": "cv-RU",
  "rows": ["йцукенгшщзхӑ", "фывапролджэӗ", "ячсмитьбюҫӳ"],
  "variants": { "е": ["ё", "ӗ"] }
}]
```

Shift and ⌫ are added to the last row automatically. Optional fields: `shift` (`{ "क": "ख" }` — what the key types with Shift
for scripts without capital letters), `dictionary` (the code of a FrequencyWords frequency dictionary, if there is one), `rtl`, `autoCorrect`.
The language appears in **Settings → Languages and dictionaries**.

## dictionary: words for suggestions

```json
"dictionary": { "en": ["cringe", "imho", "lol"] }
```

Words from a plugin dictionary are not changed by autocorrect and appear in the suggestions.

## actions: buttons on the plugins panel (up to 64)

Common action fields: `title` (required), `icon`, `type`, `pinned`.
`"pinned": true` puts the button right on the keyboard toolbar (next to 🧩).

### `insert`: insert text from a template

```json
{ "title": "Signature", "type": "insert", "text": "Best regards,{newline}John" }
```

### `transform`: transform text

What gets transformed is set by `scope`:
- `auto` (default): the selection, or, if there is none, the last word before the cursor;
- `word`: the last word;
- `text`: the whole text of the field;
- `selection`: the selection only.

| `transform` | What it does |
|---|---|
| `upper` / `lower` | UPPER / lower case |
| `title` / `sentence` | Every Word / As in a sentence |
| `reverse` | Backwards |
| `mock` | sArCaSm |
| `wrap` | Wrap: fields `prefix`, `suffix` |
| `regex` | Replace: fields `pattern`, `replacement` (groups `$1`) |
| `trim` | Remove extra spaces |
| `sort_lines` / `unique_lines` | Sort lines / remove duplicates |
| `base64_encode` / `base64_decode` | Base64 |
| `url_encode` / `url_decode` | URL encoding |
| `translit` | Cyrillic → Latin |
| `layout_swap` | Fix the keyboard layout: `ghbdtn` → `привет` |
| `strike` | S̶t̶r̶i̶k̶e̶ ̶t̶h̶r̶o̶u̶g̶h̶ |
| `spaced` | s p a c e d |
| `calc` | `2+2*3` → `2+2*3 = 8` (`+ - * / ^ %`, parentheses, `×` `÷`, comma) |
| `camel` / `snake` / `kebab` | `my new file` → `myNewFile` / `my_new_file` / `my-new-file` |
| `bold` / `italic` | 𝗕𝗼𝗹𝗱 / 𝘐𝘵𝘢𝘭𝘪𝘤 — Latin letters and digits only (Unicode characters) |
| `superscript` | `x2` → `x²` (digits, `+ - = ( ) n i`) |

### `key`: a system key or command

`tab`, `enter`, `left`, `right`, `up`, `down`, `home`, `end`, `select_all`, `copy`, `cut`, `paste`,
`undo`, `redo`, `delete_word`, `escape`.

```json
{ "title": "Select all", "type": "key", "key": "select_all" }
```

### `http`: a request to your own server

Requires `"permissions": ["network"]`. Only `https://` addresses are allowed. When such a plugin is installed,
the keyboard warns that it sends text to the network. A request is sent only when the button is pressed.

| Field | Description |
|---|---|
| `url` | The address template; substitutions in it are URL-encoded |
| `method` | `GET` (default) or `POST` |
| `body` | The body for `POST` (a template) |
| `headers` | An object of headers (values are templates) |
| `jsonPath` | The path to the answer in the JSON: `data.0.text`. Empty — the whole response as text |
| `output` | `replace` (replace the source text, default) or `insert` |
| `scope` | The same as for `transform` |

```json
{
  "title": "To emoji", "type": "http",
  "url": "https://example.com/api/emojify?q={text}",
  "jsonPath": "result"
}
```

## Templates

They work in `insert`, `shortcuts`, `url`, `body` and `headers`:

| Substitution | Value |
|---|---|
| `{date}`, `{time}`, `{datetime}` | The current date and time |
| `{date:yyyy-MM-dd}` | A date in your own format (`SimpleDateFormat`) |
| `{text}` / `{selection}` | The selected text (in `http` — the source text according to `scope`) |
| `{clipboard}` | The last copied text |
| `{random:yes\|no\|maybe}` | A random option |
| `{uuid}` | A random UUID |
| `{newline}` | A line break |

## shortcuts: autoreplace on space (up to 500)

```json
"shortcuts": { "ty": "thank you", ":date": "{date}", "<3": "❤️" }
```

The case of the trigger does not matter. A shortcut made only of letters also appears in the suggestion bar while you type it.

## symbols: symbol sets (up to 20 sets of 2000 items)

```json
"symbols": [
  { "title": "Kaomoji", "icon": "ツ", "items": ["(◕‿◕)", "¯\\_(ツ)_/¯"] }
]
```

Each set opens as a separate tab on the plugins panel.

## theme: a theme

It is applied by the "Apply look" button in the plugin card and written into the settings.

```json
"theme": {
  "seed": "#FF7A3D",
  "mode": "DARK",
  "shape": "FLOWER_6",
  "keyOpacity": 0.9,
  "colors": {
    "background": "#1A1110", "key": "#2B1F1C", "keyText": "#FFE9E2",
    "func": "#3B2A25", "funcText": "#FFDBCF", "accent": "#FF7A3D", "accentText": "#2B1100"
  }
}
```

- `mode`: `SYSTEM`, `LIGHT`, `DARK` or `AMOLED`.
- `shape`: `COOKIE_12`, `WAVY_8`, `FLOWER_6`, `CLOVER_4`, `CIRCLE` or any Material 3 shape: `SQUARE`, `SLANTED`, `ARCH`, `SEMICIRCLE`, `OVAL`, `PILL`, `TRIANGLE`, `ARROW`, `FAN`, `DIAMOND`, `CLAMSHELL`, `PENTAGON`, `GEM`, `VERY_SUNNY`, `COOKIE_4`, `COOKIE_6`, `COOKIE_7`, `COOKIE_9`, `CLOVER_8`, `BURST`, `SOFT_BURST`, `BOOM`, `SOFT_BOOM`, `PUFFY`, `PUFFY_DIAMOND`, `GHOSTISH`, `PIXEL_CIRCLE`, `PIXEL_TRIANGLE`, `BUN`, `HEART`.
- Colors are written as `#RRGGBB` or `#AARRGGBB`. If a color is not given, it is taken from the theme.

The button in the plugin card is called **"Apply look"**: it applies `theme` and `settings` and turns on `skin`
with a single tap. "Remove look" returns the normal Material 3 Expressive look.

## skin: how to draw every key

`skin` does not touch the settings — it draws the keyboard on top of them while it is on. Only one skin is active at a time.

```json
"skin": {
  "keyboard": {
    "background": { "colors": ["#1B1F4B", "#3A1C71"], "angle": 120 },
    "orbs": [ { "color": "#FF5F9E", "x": 0.15, "y": 0.4, "r": 0.3 } ],
    "corner": 32,
    "font": "mono",
    "trail": "#CCFFFFFF"
  },
  "colors": { "accent": "#8AB4FF", "keyText": "#FFFFFF" },
  "keys": {
    "*":      { "material": "glass", "press": "jelly", "text": "#FFFFFF" },
    "func":   { "glass": { "frost": 0.4 } },
    "enter":  { "background": { "colors": ["#8AB4FF", "#B388FF"], "angle": 135 }, "shape": "pill" },
    "space":  { "label": "␣" },
    "key:a":  { "material": "solid", "background": "#FF7A3D", "glow": "#FF7A3D" }
  },
  "popup": { "material": "glass", "pressedBackground": "#8AB4FF" },
  "chips": { "material": "glass", "text": "#FFFFFF" }
}
```

### keyboard — background and general

| Field | Description |
|---|---|
| `background` | A color or gradient for the keyboard background |
| `orbs` | Up to 12 soft colored spots: `color`, `x`/`y` (0..1 of the keyboard size), `r` (radius, a fraction) |
| `corner` | The rounding of the top corners of the keyboard, dp |
| `font` | `serif`, `mono`, `sans`, `cursive`, `pixel` (the pixel font Pixelify Sans) — the font of the labels |
| `trail` | The color of the trail when typing by swiping |

`colors` — the same keys as `theme.colors`, but they take effect only while the skin is on.

### keys — key styles

Selectors are applied in order, and each next one overrides the previous one:
`*` → `letter` (letters and symbols) or `func` (shift, ⌫, ?123, 🌐, 😀) → `digit` → `shift` / `backspace` / `mode` /
`globe` / `emoji` / `space` / `enter` → `key:<letter>` (for example `key:a`, `key:z`).

| Field | Values |
|---|---|
| `material` | `solid` (default), `glass` — liquid glass, `outline` — an outline, `neumorph` — raised, `bevel` — a 3D pixel button (a light edge at the top left, a dark one at the bottom right, as in Minecraft), `none` — no background |
| `background` / `pressedBackground` | A color or gradient at rest / when pressed |
| `text` / `pressedText` | The label color |
| `textGlow` | A glow around the label |
| `border`, `borderWidth` | The outline color/gradient and thickness, dp |
| `borderStyle` | `solid`, `dots` — an outline made of dots (as in Nothing), `dashed` — a dashed line |
| `labelEffect` | `dots` — letters made of dots, like on a dot matrix display; `none` — normal |
| `textShadow` | A hard label shadow with no blur (like text in Minecraft) |
| `bevel` | The thickness of the 3D edge for `material: bevel`, dp |
| `shape` | `rounded`, `pill`, `cut` (cut corners), `squircle`, `wavy` |
| `radius` / `pressedRadius` | The rounding at rest / when pressed, dp (the shape "flows") |
| `shadow`, `shadowY`, `shadowColor` | A soft shadow: blur and shift in dp (Android 9+) |
| `glow` | A glow around the key: for `outline` it is always on, for the others — when pressed |
| `press` | `scale`, `jelly` (jelly with a wobble), `bounce` (enlarging), `sink` (pressing in), `none` |
| `pressScale` | The scale when pressed for `scale`/`bounce`, 0.6..1.3 |
| `inset` | The inner margin of the cell, dp |
| `label` | Your own label (for function keys, instead of an icon) |
| `fontScale`, `fontWeight`, `italic` | Size (a multiplier), weight 100..900, italic |
| `hideHint` | Hide the small hint in the corner |
| `glass` | Glass settings, see below |

### glass — liquid glass

The glass sees the keyboard background (a gradient, `orbs`, a background picture) and refracts it at the edges.
On Android 13+ this is real shader refraction with a rainbow fringe; on older versions it is an enlarged blurred backing.

| Field | Default | Description |
|---|---|---|
| `blur` | 14 | Background blur, dp |
| `refraction` | 0.6 | The strength of refraction at the edges, 0..1 |
| `dispersion` | 0.25 | The rainbow fringe, 0..1 |
| `highlight` | 0.7 | The highlight at the top |
| `rim` | 0.7 | A light rim |
| `frost` | 0 | Frostiness: a milky haze — the key looks blurrier than the background |
| `grain` | 0 | The grain of frosted glass |
| `tint` | — | The tint color (better semi-transparent, `#33FFFFFF`) |

If `background` is set, it is drawn on top of the glass as a tint — use semi-transparent colors.

### popup and chips

`popup` — the letter preview and variants: `background`, `pressedBackground` (the chosen variant), `text`, `pressedText`,
`material: glass`, `border`, `radius` (with it, a rounded rectangle is drawn instead of the wavy "cookie").
`chips` — suggestions and the toolbar: `background`, `pressedBackground` (the middle suggestion),
`text`, `pressedText`, `border`, `borderWidth`, `material: glass`; `material: none` — suggestions as plain text
with thin dividers, as in Gboard and iOS.

### buttons — buttons at the edges of the suggestion bar

The menu and the microphone. The same format as for keys: `material` (`none` — the icon only, `solid`, `glass`, `outline`,
`neumorph`), `background`, `pressedBackground`, `text` (the icon color), `border`, `glow`, `shadow`. Without `buttons`
the buttons are drawn as wavy "cookies" in the accent color.

### panel — the backing of panels

Emoji, clipboard, voice input, translator, GIF and plugin tabs. The format is the same as for keys: `material`
(`solid`, `glass`, `outline`, `neumorph`), `background`, `border`, `borderWidth`, `shadow`, `shadowColor`, `radius`, `inset`.
Without `panel` the panels are drawn on a tonal backing with a rounding of 24.

### Blur under the keyboard

`keyboard.windowBlur` — the radius (dp) with which the keyboard blurs the app under it, `keyboard.blurTint` — a haze
on top of the blur (a semi-transparent color). It works on Android 12+ if the system allows window blur (not in
power saving mode); otherwise the normal `keyboard.background` is drawn. Glass keys show frosted glass
with a highlight and a rim when the blur is on.

### dark — the dark theme

An object with the same sections (`keyboard`, `colors`, `keys`, `popup`, `chips`, `buttons`) that is laid over
the skin when the dark theme is on. Specify only what is different:

```json
"colors": { "background": "#D1D4DA", "key": "#FFFFFF", "keyText": "#1C1C1E" },
"dark": {
  "colors": { "background": "#1C1C1E", "key": "#4A4A4E", "keyText": "#F2F2F7" },
  "keys": { "func": { "background": "#2E2E31" } }
}
```

### Gradients and patterns

A color is a string `"#RRGGBB"`. A gradient is an array `["#..", "#.."]` (top to bottom) or an object
`{ "colors": [...], "angle": 135, "type": "radial" }`. Angle: 0 — left to right, 90 — top to bottom.

A pattern is an object with `pattern`: `{ "pattern": "pixels", "colors": ["#6F6F6F", "#7A7A7A", "#646464"], "cell": 3, "seed": 5 }`.
`pixels` — random square cells of the given colors (the first one is the most common: stone, dirt), `planks` — boards with
gaps and grain, `dots` — dots of `colors[1]` on a background of `colors[0]` (a dot grid). `cell` — the cell size, dp;
`seed` — a different picture of the same pattern. A pattern suits the keyboard background, keys, buttons and panels.

### options — theme settings

Every theme can have its own settings: they appear in "Themes" → "Theme settings". Choices and switches are
JSON patches laid over the skin (objects are merged, everything else is replaced), sliders change numbers:

```json
"options": [
  { "id": "texture", "title": "Texture", "type": "choice", "default": "stone",
    "choices": [
      { "id": "stone", "title": "Stone" },
      { "id": "oak", "title": "Planks", "patch": { "keys": { "*": { "background": { "pattern": "planks", "colors": ["#A2834F", "#B8945F"] } } } } }
    ] },
  { "id": "dots", "title": "Dot letters", "type": "toggle", "default": true,
    "off": { "keys": { "*": { "labelEffect": "none" } } } },
  { "id": "frost", "title": "Frostiness", "type": "slider", "knob": "glass.frost", "min": 0, "max": 1, "default": 0.4 }
]
```

Sliders (`knob`): `radiusScale`, `shadowScale`, `glowScale`, `borderScale`, `keyAlpha`, `windowBlur`,
`glass.frost`, `glass.grain`, `glass.refraction`, `glass.highlight`, `glass.rim`, `glass.dispersion`, `glass.blur`.
Besides its own settings, every theme has common ones: rounding, shadows, transparency, glass, the press animation, the font and
"Tint with my accent color". If the user chose their own accent in the settings, the theme colors that are close to its accent (`colors.accent`)
are recolored to it.

## settings: any settings

They are applied by the "Apply look" button. The keys are the same as in the settings backup:

```json
"settings": { "key_height_dp": 58, "show_number_row": true, "theme_mode": "DARK", "color_accent": "#FF7A3D", "glide_typing": true }
```

Floating-point numbers: `key_height_dp`, `corner_radius_dp`, `key_spacing_dp`, `row_spacing_dp`, `bottom_offset_dp`,
`side_padding_dp`, `key_label_scale`, `key_opacity`, `press_animation`, `press_highlight`, `page_switch_anim`, `long_press_ms`, `vibration_ms`,
`vibration_amplitude`, `bg_dim`, `bg_blur_dp`, `one_handed_width`, `wave_amplitude_percent`, `sound_volume`.
Integers and colors (`"#hex"`): `color_background`, `color_key`, `color_key_text`, `color_func`, `color_func_text`,
`color_accent`, `color_accent_text`, `seed_color`, `wave_count`, `font_weight`, `clipboard_size`.
Yes/no: `show_number_row`, `show_key_borders`, `key_preview`, `show_hints`, `show_suggestions`, `show_mic_button`,
`show_emoji_key`, `show_globe_key`, `panel_animations`, `auto_capitalize`, `auto_correct_enabled`,
`double_space_period`, `haptics_enabled`, `vibrate_on_release`, `strong_special_keys`, `sound_enabled`,
`backspace_swipe`, `clipboard_history`, `instant_input`, `glide_typing`, `glide_trail`, `swipe_up_hint`,
`space_long_press`, `auto_pair`, `incognito`.
Strings: `theme_mode`, `accent_shape_preset`, `haptic_mode`, `font_style`, `color_source`, `one_handed`, `space_swipe`.

## variants: what appears on long press

```json
"variants": { "e": ["é", "è", "ê", "ë"], ".": ["…", "!", "?"] }
```

## layouts: your own layout

Keys: the code of any language (`ru`, `en`, `de`, `uk`…), `symbols`, `symbols2`. If the rows have no `{space}`, the plugin replaces
only the top rows, and the bottom row (?123, 🌐, ",", space, ".", enter) stays standard. With `{space}` the whole
keyboard is replaced.

```json
"layouts": {
  "en": [
    ["q", "w", "f", "p", "g", "j", "l", "u", "y", ";"],
    ["a", "r", "s", "t", "d", "h", "n", "e", "i", "o"],
    ["{shift}", "z", "x", "c", "v", "b", "k", "m", "{backspace}"]
  ]
}
```

Function keys: `{shift}`, `{backspace}`, `{space}`, `{enter}`, `{symbols}` (?123), `{abc}`, `{page}` (=\<),
`{globe}`, `{emoji}`, `{gap}`. The width goes after a colon: `{shift:1.5}`, `{gap:0.5}`.
A key of your own is an object: `{ "label": "→", "output": "->", "weight": 1.2, "variants": ["⇒", "⟶"] }`.
A mod key is `{ "label": "λ", "action": "name" }`: it calls the command registered with `kb.addCommand("name", fn)`.
The row height is an object `{ "rowHeight": 0.8 }` at the start of the row.

## Retained groups, pan/zoom and GL batches

These three features exist for mods that draw hundreds of shapes per frame (maps, boards, games). Check them with
`kb.supports("groups")`, `kb.supports("panZoom")` and `kb.supports("batch")`.

### Retained groups

Without groups a mod resends every shape in every `kb.overlay` call. With a group the shapes cross the bridge once:

```js
kb.defineGroup("map", mapShapes);            // parsed once, kept by the keyboard (up to 64 groups per mod)
kb.overlay([
  { type: "group", key: "map", world: true },  // drawn from the stored copy
  { type: "text", x: 8, y: 8, text: "Score " + score }
]);
```

A group takes `x`, `y` (dp), `scale`, `rotation` and `alpha` and moves the whole set. `world: true` also applies the camera
(see below). Calling `kb.defineGroup` again with the same key replaces the content and redraws by itself, so you do not need
to call `kb.overlay` again. A group may contain other groups (up to 4 levels). `gl` and `batch` elements inside a group are not
drawn. Groups are cleared when the plugins reload.

### Pan and zoom

```js
kb.captureTouches(true);
kb.panZoom({ min: 0.5, max: 6, mode: "two" });   // or kb.panZoom(false)
kb.on("view", function (v) { /* { x, y, zoom } — at most 10 times a second */ });
kb.on("touch", function (t) { /* t.wx, t.wy: the touch in world coordinates */ });
```

- `mode: "two"` (default): two fingers pinch to zoom and pan; one finger still goes to your `touch` handler.
- `mode: "any"`: one finger also drags the view. A touch that moves less than 8 dp is still a normal tap for your script.
- When the keyboard takes over a gesture, your script receives `touch` with `type: "cancel"` for the pointers it already got, so
  drop whatever you were doing with them.
- Only groups with `world: true` follow the camera. Everything else stays on the screen. `kb.view({ x, y, zoom })` moves the camera
  from the script (for example to centre on a place); `kb.toWorld` and `kb.toScreen` convert points.

### GL batches

`{ type: "batch", shapes: [...] }` turns many `rect`, `circle`, `oval` and `line` elements into one OpenGL mesh and draws it with a
single call. It is meant for 100+ simple shapes per frame (Compose would make one draw call for each).

```js
kb.overlay([{ type: "batch", shapes: cells.map(function (c) {
  return { type: "rect", x: c.x, y: c.y, w: 8, h: 8, color: c.color, radius: 2 };
}) }]);
```

- The shapes use the same fields as the normal elements. Translucent colours blend normally. `alpha` and `rotation` on the batch itself
  work like on any element. Optional `x`, `y`, `w`, `h` limit the batch to a rectangle; the shapes are then relative to it.
- `fill: true` lines are drawn as a fan from the centre: right for convex and star-shaped polygons only.
- `key`: `{ type: "batch", key: "terrain", shapes: [...] }` stores the mesh; later `{ type: "batch", key: "terrain" }` (no `shapes`) draws it
  again without sending the shapes. The mesh cache is shared with GL meshes (32 entries).
- No text, pixel art or images in a batch: keep those as normal elements. A batch is an OpenGL layer, so it is drawn **under** the normal
  elements of the same list, counts toward the 4 GL layers per list, and is not drawn inside `canvas` panels or groups.
- Limits: 6000 shapes and 400,000 vertices per batch. The edges are not anti-aliased, so very small circles look more angular
  than in the normal drawing.

## Big files and music

A `.zip` mod may carry music and other large files. When the archive is imported, each file is sorted by size and type:

| Kind of file | Where it goes | Limit |
|---|---|---|
| `plugin.json` | read into the plugin | 1 MB |
| the script (`main.js`) | read into the plugin | 2 MB |
| small files (under 256 KB, not audio): images, data | stored inside the plugin | 8 MB together |
| **big files**: audio (`mp3`, `ogg`, `oga`, `opus`, `wav`, `m4a`, `aac`, `flac`, `mid`, `midi`) or any file of 256 KB or more | stored as a real file in the plugin's own folder | 256 MB together |

- `kb.playAudio("music/theme.ogg", { loop: true })` plays a big file straight from disk, with no copy in memory, so a long track does not
  depend on the 8 MB limit any more.
- `kb.asset(path)` can still read a big file, but only up to 16 MB. Use `kb.playAudio` for longer ones.
- An image in the `.zip` can be a texture of a GLSL layer by its file name alone (`textures: { iTex0: "img/brick.png" }`, up to 16 MB), without `kb.asset`.
- A single `.js` or `.json` plugin file is still limited to 8 MB. Music needs the `.zip` form.
- Installing from a link accepts a `.zip` of up to 256 MB, too (see "Installing and updating from a link").

## Performance notes for mod authors

- A GL layer (`type: "gl"`) whose shader does not read `iTime`, `iFrame`, `iTouch`, `iTilt` or `iAudio` is drawn once and then only
  when you send a new item, change a uniform with `kb.glUniforms` or the keyboard is resized. Static shaders cost almost nothing.
- Uniform arrays that did not change between frames are not uploaded to the GPU again.
- GL meshes may use more than 65,536 vertices: indices above 65535 are drawn as 32-bit indices (always on OpenGL ES 3, with the
  `OES_element_index_uint` extension on ES 2). The host keeps up to 32 meshes by key, so `{ key }` resends are needed less often.
- Text in `kb.overlay` is measured through a 256-entry layout cache, so a lot of repeated text is cheap.
- For hundreds of shapes: send them once with `kb.defineGroup`, let `kb.panZoom` move the camera inside the keyboard, and draw plain
  rects, circles and lines through `{ type: "batch" }`.
- The **Script log** card shows a `gl:` line with the number of frames drawn, the number of skipped idle frames, and the average and
  maximum CPU time per frame.

## Media, Settings layout, shadows and frosted glass

### Permission `media`: now playing and player controls

Add `"media"` to `permissions`. The keyboard then sends the `media` event and gives the mod `kb.media`:

| Call | What it does |
|---|---|
| `kb.media.state()` | `{ access, active, playing, title, artist, album, app, position, duration }`. `position` and `duration` are in ms and `position` is current at the moment of the call. |
| `kb.media.art()` | The cover as a small `data:image/jpeg` URI (up to 128 px), or `""`. Use it in `{ type: "image" }` or to pick colors. |
| `kb.media.play()` / `pause()` / `toggle()` / `next()` / `previous()` | Player controls; return `true` when sent. |
| `kb.media.seek(ms)` | Jump to a position (needs Notification access). |

- Title, artist, album, cover and position need **Notification access** for the keyboard. The user turns it on in
  Settings → Plugins → Media access (on Android 13+ "Allow restricted settings" may have to be allowed first, because the APK is
  installed outside the Play Store). The keyboard reads only media sessions and never the text of notifications.
- Without that access `state()` returns `access: false`, `active: false` and only `playing`
  (whether any music is playing), and the controls fall back to the system media keys.
- Nothing is delivered in incognito mode and in password fields. `kb.supports("media")` — the mod has the permission,
  `kb.supports("mediaInfo")` — and Notification access is on.
- A mod that has both `media` and `network` is a privacy risk (listening history could leave the phone): ask for both only if
  you really need both.

```js
kb.on("media", m => {
  kb.status(m.playing ? "♪ " + m.title + " — " + m.artist : "");
});
```

### Permission `settings_layout`: arrange the Settings home screen

A plugin with `"settings_layout"` in `permissions` may add a `settingsLayout` block to the manifest:

```json
{ "id": "my-order", "name": "My order", "permissions": ["settings_layout"],
  "settingsLayout": {
    "pinned": ["themes", "colors"],
    "order":  ["layout", "keys", "size"],
    "hidden": ["power"] } }
```

- `pinned` — pages shown first in their own section named after the plugin. `order` — the order of the remaining pages inside
  their groups. `hidden` — pages removed from the home screen (the Settings search still finds them).
- Page names: `whats_new`, `themes`, `theme_editor`, `colors`, `size`, `keys`, `layout`, `long_press`, `animations`,
  `toolbar`, `languages`, `my_words`, `typing`, `gestures`, `clipboard`, `feedback`, `power`, `plugins`, `backup`, `about`.
- `plugins`, `backup` and `about` can never be hidden. The home screen shows a row to restore the normal order, and the user can
  turn the whole feature off in Settings → Plugins → Plugin security. If several plugins have a block, the first enabled one wins.
- This changes only the order and visibility of pages, not what is inside them.

### Permission `toolbar_layout`: arrange the keyboard toolbar

A plugin with `"toolbar_layout"` in `permissions` may add a `toolbarLayout` block to the manifest:

```json
{ "id": "my-toolbar", "name": "My toolbar", "permissions": ["toolbar_layout"],
  "toolbarLayout": {
    "order":  ["button:go", "emoji", "clipboard", "gif"],
    "hidden": ["translate", "one_handed"] } }
```

- `order` - buttons shown first, in this order. Buttons not named keep the user's own order after them.
- `hidden` - buttons removed from the toolbar.
- Button ids: `emoji`, `gif`, `clipboard`, `translate`, `ai_fix`, `one_handed`, `floating`, `incognito`, `hide`, `plugins`, `settings`.
  Use `button:<id>` for a toolbar button your own script added (the same as `btn:<plugin id>:<id>` in the full form).
- `plugins` and `settings` can never be hidden. While a plugin arranges the toolbar, dragging buttons on the keyboard is off.
  The Toolbar settings page has a row to go back to the user's own order, and the user can turn the whole feature off in
  Settings -> Plugins -> Plugin security. If several plugins have a block, the first enabled one wins.

### Dynamic shadow and static frosted glass

Both are user settings (Settings → Colors and background), and a plugin may switch them in its `settings` block:

| Key | Meaning |
|---|---|
| `dynamic_shadow` (`true`/`false`) | Key shadows lean away from the tilt of the phone. Works for keys drawn by a theme or skin; flat keys get a soft shadow. The motion sensor is used only while the keyboard is open. `kb.supports("dynamicShadow")` is `true`. |
| `bg_frost_static` (`true`/`false`) | The background picture is blurred once (with the existing blur strength, `bg_blur_dp`) instead of live. No battery cost, works on every Android version. |

### Security: red dot and reminder

The keyboard draws a small red dot, above everything a plugin can draw, while a plugin is actually using a sensitive
permission (`read_text`, `clipboard`, `microphone`, `sensors`, `media`, `app_info`). A warning banner with a red icon appears
every few minutes (default 4) while such a plugin is enabled; the user can change the interval or switch the reminder off in
Settings → Plugins → Plugin security. With the reminder off the dot still shows for the microphone and for `network` combined with
`read_text`, `clipboard` or `media`. Plugins cannot change these settings.

