-- Bridge between the keyboard (Kotlin) and a Lua plugin.
-- Values no longer cross the boundary as JSON: plugin_lua.c converts Lua tables, strings and numbers to Java objects
-- and back directly, and keeps the Lua callbacks handed to Kotlin in a registry table. What is left here is the
-- plugin-facing JSON helpers (kb.json) and the two functions the native side calls:
--   install(bindings)   makes the keyboard's functions and tables Lua globals
--   jsonDecode(text)    parses JSON text into plain tables (null becomes nil), used for ScriptJson values
-- The chunk returns a table holding both.

local G = _G
local type, pairs, error, tostring, tonumber = type, pairs, error, tostring, tonumber
local math_type, tointeger, huge = math.type, math.tointeger, math.huge
local sfind, ssub, sbyte, sformat, sgsub = string.find, string.sub, string.byte, string.format, string.gsub
local concat = table.concat
local uchar = utf8.char

local NULL = setmetatable({}, { __tostring = function() return "null" end })

-- ---------------------------------------------------------------- JSON decode
local ESCAPES = { ['"'] = '"', ['\\'] = '\\', ['/'] = '/', b = '\b', f = '\f', n = '\n', r = '\r', t = '\t' }

local function skip(s, i)
    return sfind(s, "[^ \t\r\n]", i) or #s + 1
end

local parse_value

local function parse_string(s, i)
    -- s:sub(i, i) is the opening quote
    local out, n, start = {}, 0, i + 1
    while true do
        local j = sfind(s, '["\\]', start)
        if not j then error("unterminated string", 0) end
        if j > start then n = n + 1; out[n] = ssub(s, start, j - 1) end
        if sbyte(s, j) == 34 then return concat(out), j + 1 end
        local e = ssub(s, j + 1, j + 1)
        if e == "u" then
            local hex = ssub(s, j + 2, j + 5)
            if not sfind(hex, "^%x%x%x%x$") then error("bad \\u escape", 0) end
            local code = tonumber(hex, 16)
            start = j + 6
            if code >= 0xD800 and code <= 0xDBFF and ssub(s, start, start + 1) == "\\u" then
                local low = tonumber(ssub(s, start + 2, start + 5), 16)
                if low and low >= 0xDC00 and low <= 0xDFFF then
                    code = 0x10000 + (code - 0xD800) * 0x400 + (low - 0xDC00)
                    start = start + 6
                end
            end
            if code >= 0xD800 and code <= 0xDFFF then code = 0xFFFD end
            n = n + 1; out[n] = uchar(code)
        else
            local c = ESCAPES[e]
            if not c then error("bad escape", 0) end
            n = n + 1; out[n] = c
            start = j + 2
        end
    end
end

function parse_value(s, i, depth)
    if depth > 200 then error("JSON is nested too deep", 0) end
    i = skip(s, i)
    local c = sbyte(s, i)
    if c == 123 then -- {
        local obj = {}
        i = skip(s, i + 1)
        if sbyte(s, i) == 125 then return obj, i + 1 end
        while true do
            if sbyte(s, i) ~= 34 then error("expected a string key", 0) end
            local key
            key, i = parse_string(s, i)
            i = skip(s, i)
            if sbyte(s, i) ~= 58 then error("expected ':'", 0) end
            local value
            value, i = parse_value(s, i + 1, depth + 1)
            if value ~= NULL then obj[key] = value end
            i = skip(s, i)
            local d = sbyte(s, i)
            if d == 125 then return obj, i + 1 end
            if d ~= 44 then error("expected ',' or '}'", 0) end
            i = skip(s, i + 1)
        end
    elseif c == 91 then -- [
        local arr, n = {}, 0
        i = skip(s, i + 1)
        if sbyte(s, i) == 93 then return arr, i + 1 end
        while true do
            local value
            value, i = parse_value(s, i, depth + 1)
            n = n + 1; arr[n] = value
            i = skip(s, i)
            local d = sbyte(s, i)
            if d == 93 then return arr, i + 1 end
            if d ~= 44 then error("expected ',' or ']'", 0) end
            i = i + 1
        end
    elseif c == 34 then
        return parse_string(s, i)
    elseif c == 116 and ssub(s, i, i + 3) == "true" then
        return true, i + 4
    elseif c == 102 and ssub(s, i, i + 4) == "false" then
        return false, i + 5
    elseif c == 110 and ssub(s, i, i + 3) == "null" then
        return NULL, i + 4
    else
        local num = s:match("^-?%d+%.?%d*[eE]?[+-]?%d*", i)
        if not num or num == "" then error("unexpected character at " .. i, 0) end
        local v = tonumber(num)
        if not v then error("bad number", 0) end
        -- JSON has one number type: whole values become Lua integers, so 5 prints as 5 and not 5.0
        if math_type(v) == "float" then v = tointeger(v) or v end
        return v, i + #num
    end
end

-- A decoded list may hold NULL placeholders (so that #list stays right); decode_plain() turns them into nil.
local function json_parse(s)
    local value, i = parse_value(s, 1, 0)
    if skip(s, i) <= #s then error("extra data after the JSON value", 0) end
    return value
end

-- ---------------------------------------------------------------- JSON encode
local ESC = { ['"'] = '\\"', ['\\'] = '\\\\', ['\b'] = '\\b', ['\f'] = '\\f', ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t' }
local function esc(c) return ESC[c] or sformat("\\u%04x", sbyte(c)) end
local function quote(s) return '"' .. sgsub(s, '[%c"\\]', esc) .. '"' end

local function is_list(t)
    local n = 0
    for k in pairs(t) do
        if math_type(k) ~= "integer" or k < 1 then return false end
        n = n + 1
    end
    return n == #t
end

local function encode_number(v)
    if math_type(v) == "integer" then return tostring(v) end
    local i = tointeger(v)
    if i and v > -1e15 and v < 1e15 then return tostring(i) end
    return sformat("%.17g", v)
end

-- plain JSON (kb.json.decode and storage): objects and arrays as they are, null as nil
local function decode_plain(value)
    if value == NULL then return nil end
    if type(value) ~= "table" then return value end
    local out = {}
    for k, v in pairs(value) do
        if v ~= NULL then out[k] = decode_plain(v) end
    end
    return out
end

local function json_decode(text)
    return decode_plain(json_parse(tostring(text)))
end

local function json_encode_plain(value, depth)
    depth = depth or 0
    local t = type(value)
    if value == nil or depth > 32 then return "null" end
    if t == "string" then return quote(value) end
    if t == "boolean" then return value and "true" or "false" end
    if t == "number" then
        if value ~= value or value == huge or value == -huge then return "null" end
        return encode_number(value)
    end
    if t == "table" then
        local parts, n = {}, 0
        if is_list(value) then
            for i = 1, #value do n = n + 1; parts[n] = json_encode_plain(value[i], depth + 1) end
            return "[" .. concat(parts, ",") .. "]"
        end
        for k, v in pairs(value) do
            if type(v) ~= "function" then
                n = n + 1
                parts[n] = quote(tostring(k)) .. ":" .. json_encode_plain(v, depth + 1)
            end
        end
        return "{" .. concat(parts, ",") .. "}"
    end
    return "null"
end

local function json_stringify(value) return json_encode_plain(value, 0) end

-- ---------------------------------------------------------------- entry points for the native side
return {
    install = function(bindings)
        for name, value in pairs(bindings) do G[name] = value end
        if type(G.kb) == "table" then
            G.kb.json = { decode = json_decode, encode = json_stringify }
        end
    end,
    jsonDecode = json_decode,
}
