--[=[ @plugin
{
  "id": "example.hello_lua",
  "name": "Hello Lua",
  "version": "1.0",
  "description": "Example mod written in Lua 5.4: a toolbar button, a counter kept between runs, a word event",
  "icon": "🌙",
  "permissions": ["read_text"]
}
]=]

-- The manifest above is the same plugin JSON as in a .js mod, written inside a Lua long comment.
-- The engine is chosen by the comment itself ("--[=[ @plugin"), so "scriptEngine" is not needed.
-- The script has the same kb.* functions as a JavaScript mod: tables stand for objects and lists,
-- functions are passed as callbacks. Lua lists start at 1.

-- Stored values are Lua tables / numbers / strings, kept between runs
local state = kb.storage.get("state", { taps = 0, words = 0 })

kb.addButton({
  title = "Hello Lua",
  icon = "🌙",
  toolbar = true,
  onClick = function()
    state.taps = state.taps + 1
    kb.storage.set("state", state)
    kb.toast(string.format("%s: tap #%d, %d words typed", _VERSION, state.taps, state.words))
  end,
})

-- An event after every typed word (needs the read_text permission)
kb.on("word", function(e)
  state.words = state.words + 1
  if state.words % 10 == 0 then kb.storage.set("state", state) end
end)

-- print() and console.log() write to the Script log (Plugins & mods, below the mod's settings)
print("Hello Lua loaded, " .. state.taps .. " taps so far")
