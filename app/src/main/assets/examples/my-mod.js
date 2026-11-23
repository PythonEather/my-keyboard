/* @plugin
{
  "id": "my.first.mod",
  "name": "My first mod",
  "version": "1.0",
  "author": "Me",
  "description": "Example mod: its own panel, reacting to words, custom suggestions and storage",
  "icon": "🚀",
  "permissions": ["read_text"],
  "layouts": {
    "symbols2": [
      ["~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"],
      ["£", "€", "$", "¥", "^", "°", "=", "{", "}", "\\"],
      ["{page}", "%", "©", "®", "™", { "label": "λ", "action": "lambda" }, "[", "]", "{backspace}"]
    ]
  }
}
*/

// The manifest above is a normal plugin JSON: you can use everything described in PLUGINS.md
// (actions, shortcuts, symbols, theme, skin, settings, layouts, icons, languages…).
// The script is below. It runs in a sandbox with the kb object, console and setTimeout/setInterval.

// The typed word counter is kept between runs
var stats = kb.storage.get("stats", { words: 0 });

// 1. An event after every typed word (needs the read_text permission)
kb.on("word", function (e) {
  stats.words++;
  if (stats.words % 10 === 0) kb.storage.set("stats", stats);
});

// 2. Custom suggestions: while you type "ty", extra options appear on the sides of the suggestion bar
kb.on("suggest", function (e) {
  if (e.word.toLowerCase() === "ty") return ["thank you so much", "much appreciated"];
});

// 3. Replace while typing: two dots in a row turn into an ellipsis
kb.on("key", function (e) {
  if (e.text === "." && kb.textBefore(1) === ".") {
    kb.deleteBefore(1);
    return "…";            // return a string to type it instead of the key; return false to cancel the press
  }
});

// 4. Your own panel: render() returns a list of elements, kb.refresh() redraws it
kb.addPanel({
  id: "main",
  title: "My mod",
  icon: "🚀",
  render: function () {
    return [
      { type: "text", text: "Words typed: " + stats.words, size: 18, bold: true },
      { type: "row", children: [
        { type: "button", text: "Date", icon: "@date", onClick: function () { kb.commit(new Date().toLocaleDateString()); } },
        { type: "button", text: "Reset", icon: "@refresh", onClick: function () {
          stats.words = 0;
          kb.storage.set("stats", stats);
          kb.refresh();
        } }
      ] },
      { type: "grid", items: ["😀", "🔥", "👍", "❤️", "🎉", "🤔"], onClick: function (item) { kb.commit(item); } }
    ];
  }
});

// 5. A command for your own key: the λ key in the layout above calls it
kb.addCommand("lambda", function () { kb.commit("λ"); });

console.log("My mod loaded");
