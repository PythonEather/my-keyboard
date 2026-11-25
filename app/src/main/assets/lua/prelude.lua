-- Runs once before a Lua plugin: hardening and the helpers that the JavaScript prelude gives JS plugins.

-- load() accepts text only: precompiled chunks can crash the VM
local raw_load = load
load = function(chunk, name, _, env)
    if env == nil then env = _ENV end
    return raw_load(chunk, name or "=load", "t", env)
end

-- print goes to the Script log
print = function(...)
    local n = select("#", ...)
    local parts = {}
    for i = 1, n do parts[i] = tostring((select(i, ...))) end
    kb.log(table.concat(parts, "\t"))
end

-- storage keeps Lua values (kb.storage.get returns the stored JSON text, the JS prelude parses it, so does this)
do
    local storage = kb.storage
    if storage then
        local read, write = storage.get, storage.set
        storage.get = function(key, fallback)
            local raw = read(key)
            if raw == nil then return fallback end
            local ok, value = pcall(kb.json.decode, raw)
            if ok and value ~= nil then return value end
            return fallback
        end
        storage.set = function(key, value) write(key, kb.json.encode(value)) end
    end
end

-- require("lib.maze") or require("lib/maze.lua"): other .lua files from the plugin's folder or .zip
do
    local modules, loading = {}, {}
    local asset = kb.asset
    local function normalize(path)
        local out = {}
        for part in string.gmatch(path, "[^/]+") do
            if part == ".." then
                if #out == 0 then error("require: \"" .. path .. "\" leaves the plugin folder", 3) end
                out[#out] = nil
            elseif part ~= "." then
                out[#out + 1] = part
            end
        end
        return table.concat(out, "/")
    end
    function require(name)
        name = tostring(name)
        local base = name
        if not string.find(name, "/", 1, true) and not string.find(name, "%.lua$") then
            base = string.gsub(name, "%.", "/")
        end
        base = normalize(base)
        local tried = { base, base .. ".lua", base .. "/init.lua" }
        for _, path in ipairs(tried) do
            if modules[path] ~= nil then return modules[path] end
            if loading[path] then error("require: \"" .. path .. "\" requires itself (circular require)", 2) end
            local source = asset and asset(path)
            if type(source) == "string" and string.sub(source, 1, 5) ~= "data:" then
                loading[path] = true
                local chunk, message = raw_load(source, "=" .. path, "t", _ENV)
                if not chunk then loading[path] = nil; error(message, 0) end
                local ok, result = pcall(chunk, name, path)
                loading[path] = nil
                if not ok then error(result, 0) end
                if result == nil then result = true end
                modules[path] = result
                return result
            end
        end
        error("require: no file \"" .. base .. "\" in the plugin (looked for " .. table.concat(tried, ", ") .. ")", 2)
    end
end
