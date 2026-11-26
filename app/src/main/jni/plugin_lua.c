#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "lua/lua.h"
#include "lua/lauxlib.h"
#include "lua/lualib.h"

/*
 * Lua 5.4 for plugins. Same contract as plugin_quickjs.c for source text (the Kotlin side hands over source and gets
 * [status byte][text] back), but VALUES no longer travel as JSON text: Lua tables, strings and numbers are converted
 * straight to Java objects and back through JNI.
 *
 * Java side of the wire (see LuaPluginRuntime.kt):
 *   null / String / Boolean / Double     as they are (Lua integers and floats both become Double)
 *   Object[]                             a list (Lua table whose keys are exactly 1..n)
 *   LuaMap(flat)                         a table: flat = key0, value0, key1, value1, ... (String keys)
 *   LuaHostRef(id, name)                 a Kotlin function, seen from Lua as a callable
 *   LuaFnRef(id)                         a Lua function, kept in a registry table and called back by id
 *   LuaJsonText(text)                    JSON text that Lua parses into plain tables (kb.json.decode rules)
 *   LuaHostError(message)                returned by dispatchHost when the Kotlin function threw
 *
 * Safety: only base/coroutine/table/string/utf8/math are opened (no io, os, debug, package), memory is capped,
 * and a count hook stops scripts that run past the deadline. After a stop the hook fires on EVERY instruction, so a
 * script cannot keep running by wrapping the endless loop in pcall. Table conversion runs in C, where that hook
 * cannot fire, so it checks the deadline itself.
 *
 * Every conversion runs inside lua_pcall (a Lua error here must never reach an unprotected longjmp), and none of
 * them calls metamethods (no __pairs, __len or __tostring), so a plugin cannot run code in the middle of one.
 */

enum RunMode { BOOTSTRAP, EVAL };

#define MEMORY_LIMIT (64u * 1024u * 1024u)
#define HOOK_COUNT 10000
#define DATA_LIMIT (16u * 1024u * 1024u)   /* string bytes per host call or result */
#define DATA_NODES 2000000u                 /* values (numbers, strings, tables) per host call or result */
#define MAX_DEPTH 32                        /* nesting converted from Lua; deeper values become nil */
#define MAX_JAVA_DEPTH 64

typedef struct {
    lua_State *L;
    JNIEnv *env;
    jobject owner;
    jmethodID dispatch_host;
    int64_t deadline;
    int depth;
    int interrupted;
    int bridge_install;       /* registry reference: install(bindings) from plugin-bridge.lua */
    int bridge_json;          /* registry reference: jsonDecode(text) from plugin-bridge.lua */
    int callbacks;            /* registry reference of the table id -> Lua function handed to Kotlin */
    lua_Integer next_callback;
    size_t used;
} PluginRuntime;

/* ------------------------------------------------------------------ JNI lookups (once per process) */

typedef struct {
    int ready;
    jclass object, object_array, string, number, boolean_, illegal_state;
    jclass map, host_ref, fn_ref, json_text, host_error;
    jobject bool_true, bool_false;
    jmethodID double_value_of, number_double_value, boolean_value;
    jmethodID map_init, fn_ref_init, illegal_state_init;
    jfieldID map_flat, host_id, host_name, fn_id, json_text_field, error_message;
    jclass double_;
} Jni;

static Jni J;
static pthread_mutex_t jni_lock = PTHREAD_MUTEX_INITIALIZER;

static jclass global_class(JNIEnv *env, const char *name) {
    jclass local = (*env)->FindClass(env, name);
    if (!local) return NULL;
    jclass global = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    return global;
}

static int jni_init(JNIEnv *env) {
    int ok = 0;
    pthread_mutex_lock(&jni_lock);
    if (J.ready) {
        pthread_mutex_unlock(&jni_lock);
        return 1;
    }
    Jni j;
    memset(&j, 0, sizeof(j));
    j.object = global_class(env, "java/lang/Object");
    j.object_array = global_class(env, "[Ljava/lang/Object;");
    j.string = global_class(env, "java/lang/String");
    j.number = global_class(env, "java/lang/Number");
    j.double_ = global_class(env, "java/lang/Double");
    j.boolean_ = global_class(env, "java/lang/Boolean");
    j.illegal_state = global_class(env, "java/lang/IllegalStateException");
    j.map = global_class(env, "com/expressive/keyboard/LuaMap");
    j.host_ref = global_class(env, "com/expressive/keyboard/LuaHostRef");
    j.fn_ref = global_class(env, "com/expressive/keyboard/LuaFnRef");
    j.json_text = global_class(env, "com/expressive/keyboard/LuaJsonText");
    j.host_error = global_class(env, "com/expressive/keyboard/LuaHostError");
    if (!j.object || !j.object_array || !j.string || !j.number || !j.double_ || !j.boolean_ || !j.illegal_state ||
        !j.map || !j.host_ref || !j.fn_ref || !j.json_text || !j.host_error) goto done;

    j.double_value_of = (*env)->GetStaticMethodID(env, j.double_, "valueOf", "(D)Ljava/lang/Double;");
    j.number_double_value = (*env)->GetMethodID(env, j.number, "doubleValue", "()D");
    j.boolean_value = (*env)->GetMethodID(env, j.boolean_, "booleanValue", "()Z");
    j.map_init = (*env)->GetMethodID(env, j.map, "<init>", "([Ljava/lang/Object;)V");
    j.fn_ref_init = (*env)->GetMethodID(env, j.fn_ref, "<init>", "(I)V");
    j.illegal_state_init = (*env)->GetMethodID(env, j.illegal_state, "<init>", "(Ljava/lang/String;)V");
    j.map_flat = (*env)->GetFieldID(env, j.map, "flat", "[Ljava/lang/Object;");
    j.host_id = (*env)->GetFieldID(env, j.host_ref, "id", "I");
    j.host_name = (*env)->GetFieldID(env, j.host_ref, "name", "Ljava/lang/String;");
    j.fn_id = (*env)->GetFieldID(env, j.fn_ref, "id", "I");
    j.json_text_field = (*env)->GetFieldID(env, j.json_text, "text", "Ljava/lang/String;");
    j.error_message = (*env)->GetFieldID(env, j.host_error, "message", "Ljava/lang/String;");
    if (!j.double_value_of || !j.number_double_value || !j.boolean_value || !j.map_init || !j.fn_ref_init ||
        !j.illegal_state_init || !j.map_flat || !j.host_id || !j.host_name || !j.fn_id || !j.json_text_field ||
        !j.error_message) goto done;

    jfieldID true_field = (*env)->GetStaticFieldID(env, j.boolean_, "TRUE", "Ljava/lang/Boolean;");
    jfieldID false_field = (*env)->GetStaticFieldID(env, j.boolean_, "FALSE", "Ljava/lang/Boolean;");
    if (!true_field || !false_field) goto done;
    jobject t = (*env)->GetStaticObjectField(env, j.boolean_, true_field);
    jobject f = (*env)->GetStaticObjectField(env, j.boolean_, false_field);
    if (!t || !f) goto done;
    j.bool_true = (*env)->NewGlobalRef(env, t);
    j.bool_false = (*env)->NewGlobalRef(env, f);
    (*env)->DeleteLocalRef(env, t);
    (*env)->DeleteLocalRef(env, f);
    if (!j.bool_true || !j.bool_false) goto done;

    j.ready = 1;
    J = j;
    ok = 1;
done:
    pthread_mutex_unlock(&jni_lock);
    return ok;
}

/* ------------------------------------------------------------------ runtime plumbing */

static int64_t monotonic_ms(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return (int64_t)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

static void *limited_alloc(void *ud, void *ptr, size_t osize, size_t nsize) {
    PluginRuntime *plugin = ud;
    if (nsize == 0) {
        if (ptr) plugin->used -= osize;
        free(ptr);
        return NULL;
    }
    size_t old = ptr ? osize : 0;   /* with a NULL block osize is a type tag, not a size */
    if (plugin->used - old + nsize > MEMORY_LIMIT) return NULL;
    void *block = realloc(ptr, nsize);
    if (block) plugin->used = plugin->used - old + nsize;
    return block;
}

static void deadline_hook(lua_State *L, lua_Debug *ar) {
    (void)ar;
    PluginRuntime *plugin = NULL;
    lua_getallocf(L, (void **)&plugin);
    if (!plugin->interrupted && monotonic_ms() >= plugin->deadline) {
        plugin->interrupted = 1;
        lua_sethook(L, deadline_hook, LUA_MASKCOUNT, 1);
    }
    if (plugin->interrupted) luaL_error(L, "script ran too long and was stopped");
}

static PluginRuntime *plugin_of(lua_State *L) {
    PluginRuntime *plugin = NULL;
    lua_getallocf(L, (void **)&plugin);
    return plugin;
}

static void stop_everything(PluginRuntime *plugin) {
    plugin->interrupted = 1;
    lua_sethook(plugin->L, deadline_hook, LUA_MASKCOUNT, 1);
}

/* ------------------------------------------------------------------ text: Java UTF-16 <-> Lua UTF-8 */

/* out needs room for 3 bytes per UTF-16 unit. A lone surrogate becomes U+FFFD. */
static size_t utf16_to_utf8(const jchar *s, jsize n, char *out) {
    char *p = out;
    for (jsize i = 0; i < n; i++) {
        uint32_t c = s[i];
        if (c < 0x80) {
            *p++ = (char)c;
        } else if (c < 0x800) {
            *p++ = (char)(0xC0 | (c >> 6));
            *p++ = (char)(0x80 | (c & 0x3F));
        } else {
            if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && s[i + 1] >= 0xDC00 && s[i + 1] <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (uint32_t)(s[i + 1] - 0xDC00);
                i++;
                *p++ = (char)(0xF0 | (c >> 18));
                *p++ = (char)(0x80 | ((c >> 12) & 0x3F));
                *p++ = (char)(0x80 | ((c >> 6) & 0x3F));
                *p++ = (char)(0x80 | (c & 0x3F));
                continue;
            }
            if (c >= 0xD800 && c <= 0xDFFF) c = 0xFFFD;
            *p++ = (char)(0xE0 | (c >> 12));
            *p++ = (char)(0x80 | ((c >> 6) & 0x3F));
            *p++ = (char)(0x80 | (c & 0x3F));
        }
    }
    return (size_t)(p - out);
}

/* out needs room for one UTF-16 unit per input byte. Malformed bytes become U+FFFD, one per byte. */
static jsize utf8_to_utf16(const unsigned char *s, size_t n, jchar *out) {
    jchar *p = out;
    size_t i = 0;
    while (i < n) {
        uint32_t c = s[i];
        if (c < 0x80) {
            *p++ = (jchar)c;
            i++;
            continue;
        }
        size_t need;
        uint32_t cp;
        if (c >= 0xC2 && c <= 0xDF) { need = 1; cp = c & 0x1F; }
        else if (c >= 0xE0 && c <= 0xEF) { need = 2; cp = c & 0x0F; }
        else if (c >= 0xF0 && c <= 0xF4) { need = 3; cp = c & 0x07; }
        else { *p++ = 0xFFFD; i++; continue; }
        int valid = i + need < n;
        for (size_t k = 1; valid && k <= need; k++) {
            if ((s[i + k] & 0xC0) != 0x80) valid = 0;
            else cp = (cp << 6) | (s[i + k] & 0x3F);
        }
        if (valid && need == 2 && (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF))) valid = 0;
        if (valid && need == 3 && (cp < 0x10000 || cp > 0x10FFFF)) valid = 0;
        if (!valid) { *p++ = 0xFFFD; i++; continue; }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            *p++ = (jchar)(0xD800 + (cp >> 10));
            *p++ = (jchar)(0xDC00 + (cp & 0x3FF));
        } else {
            *p++ = (jchar)cp;
        }
        i += need + 1;
    }
    return (jsize)(p - out);
}

static jstring make_jstring(JNIEnv *env, const char *s, size_t len) {
    jchar stack[256];
    jchar *buf = stack;
    if (len > 256) {
        buf = malloc(len * sizeof(jchar));
        if (!buf) return NULL;
    }
    jsize n = utf8_to_utf16((const unsigned char *)s, len, buf);
    jstring result = (*env)->NewString(env, buf, n);
    if (buf != stack) free(buf);
    return result;
}

/* Pushes the Java string as a Lua string. The buffer is reserved BEFORE the characters are borrowed, so a Lua
 * memory error cannot happen while the JVM still holds them. */
static void push_jstring(lua_State *L, JNIEnv *env, jstring str) {
    if (!str) {
        lua_pushliteral(L, "");
        return;
    }
    jsize n = (*env)->GetStringLength(env, str);
    if (n == 0) {
        lua_pushliteral(L, "");
        return;
    }
    luaL_Buffer b;
    char *dest = luaL_buffinitsize(L, &b, (size_t)n * 3);
    const jchar *chars = (*env)->GetStringChars(env, str, NULL);
    if (!chars) {
        luaL_pushresultsize(&b, 0);   /* the Java exception stays pending; the caller's check catches it */
        return;
    }
    size_t length = utf16_to_utf8(chars, n, dest);
    (*env)->ReleaseStringChars(env, str, chars);
    luaL_pushresultsize(&b, length);
}

/* ------------------------------------------------------------------ Lua -> Java */

typedef struct {
    PluginRuntime *plugin;
    size_t budget;     /* string bytes still allowed */
    unsigned nodes;
    int failed;
    int too_big;
    int timeout;
} Convert;

static jobject java_from_lua(lua_State *L, JNIEnv *env, int idx, int depth, Convert *ctx);

/* A copy of the value's text on top of the stack, without calling metamethods. */
static void push_plain_text(lua_State *L, int idx) {
    switch (lua_type(L, idx)) {
        case LUA_TSTRING:
            lua_pushvalue(L, idx);
            break;
        case LUA_TNUMBER:
            lua_pushvalue(L, idx);
            lua_tolstring(L, -1, NULL);   /* converts the copy in place */
            break;
        case LUA_TBOOLEAN:
            lua_pushstring(L, lua_toboolean(L, idx) ? "true" : "false");
            break;
        case LUA_TNIL:
            lua_pushliteral(L, "nil");
            break;
        default:
            lua_pushfstring(L, "%s: %p", luaL_typename(L, idx), lua_topointer(L, idx));
    }
}

static jstring jstring_from_lua(JNIEnv *env, const char *text, size_t len, Convert *ctx) {
    if (len > ctx->budget) {
        ctx->failed = ctx->too_big = 1;
        return NULL;
    }
    ctx->budget -= len;
    jstring result = make_jstring(env, text, len);
    if (!result) ctx->failed = 1;
    return result;
}

static jobject function_to_java(lua_State *L, JNIEnv *env, int idx, Convert *ctx) {
    PluginRuntime *plugin = ctx->plugin;
    if (plugin->next_callback >= 2147483647) {
        lua_pushliteral(L, "Too many Lua callbacks");
        lua_error(L);
    }
    lua_Integer id = ++plugin->next_callback;
    lua_rawgeti(L, LUA_REGISTRYINDEX, plugin->callbacks);
    lua_pushvalue(L, idx);
    lua_rawseti(L, -2, id);
    lua_pop(L, 1);
    jobject ref = (*env)->NewObject(env, J.fn_ref, J.fn_ref_init, (jint)id);
    if (!ref) ctx->failed = 1;
    return ref;
}

static jobject table_to_java(lua_State *L, JNIEnv *env, int idx, int depth, Convert *ctx) {
    if (!lua_checkstack(L, 8)) return NULL;
    int top = lua_gettop(L);
    lua_Integer count = 0;
    int integer_keys = 1;
    lua_pushnil(L);
    while (lua_next(L, idx)) {
        count++;
        if (integer_keys && !(lua_type(L, -2) == LUA_TNUMBER && lua_isinteger(L, -2) && lua_tointeger(L, -2) >= 1)) {
            integer_keys = 0;
        }
        lua_pop(L, 1);
    }
    if (count > 100000000) {
        ctx->failed = ctx->too_big = 1;
        return NULL;
    }

    if (integer_keys && (lua_Unsigned)count == lua_rawlen(L, idx)) {
        jobjectArray list = (*env)->NewObjectArray(env, (jsize)count, J.object, NULL);
        if (!list) {
            ctx->failed = 1;
            return NULL;
        }
        for (lua_Integer i = 1; i <= count; i++) {
            lua_rawgeti(L, idx, i);
            jobject item = java_from_lua(L, env, -1, depth + 1, ctx);
            lua_pop(L, 1);
            if (item) {
                (*env)->SetObjectArrayElement(env, list, (jsize)(i - 1), item);
                (*env)->DeleteLocalRef(env, item);
            }
            if (ctx->failed || (*env)->ExceptionCheck(env)) {
                ctx->failed = 1;
                (*env)->DeleteLocalRef(env, list);
                return NULL;
            }
        }
        return list;
    }

    jobjectArray flat = (*env)->NewObjectArray(env, (jsize)(count * 2), J.object, NULL);
    if (!flat) {
        ctx->failed = 1;
        return NULL;
    }
    jsize pos = 0;
    lua_pushnil(L);
    while (lua_next(L, idx)) {
        size_t len;
        const char *text;
        int copy = 0;
        if (lua_type(L, -2) == LUA_TSTRING) {
            text = lua_tolstring(L, -2, &len);
        } else {
            push_plain_text(L, -2);   /* never convert the key lua_next is walking */
            text = lua_tolstring(L, -1, &len);
            copy = 1;
        }
        jstring key = jstring_from_lua(env, text, len, ctx);
        if (copy) lua_pop(L, 1);
        jobject value = key ? java_from_lua(L, env, -1, depth + 1, ctx) : NULL;
        if (key && !ctx->failed) {
            (*env)->SetObjectArrayElement(env, flat, pos, key);
            if (value) (*env)->SetObjectArrayElement(env, flat, pos + 1, value);
            pos += 2;
        }
        if (key) (*env)->DeleteLocalRef(env, key);
        if (value) (*env)->DeleteLocalRef(env, value);
        if (ctx->failed || (*env)->ExceptionCheck(env)) {
            ctx->failed = 1;
            lua_settop(L, top);
            (*env)->DeleteLocalRef(env, flat);
            return NULL;
        }
        lua_pop(L, 1);   /* the value; the key stays for lua_next */
    }
    jobject map = (*env)->NewObject(env, J.map, J.map_init, flat);
    (*env)->DeleteLocalRef(env, flat);
    if (!map) ctx->failed = 1;
    return map;
}

/* Returns a new local reference, or NULL for nil (and for failure, with ctx->failed set). */
static jobject java_from_lua(lua_State *L, JNIEnv *env, int idx, int depth, Convert *ctx) {
    idx = lua_absindex(L, idx);
    if (ctx->failed || depth > MAX_DEPTH) return NULL;
    if (++ctx->nodes > DATA_NODES) {   /* the old JSON bridge stopped these at 16 MiB of text */
        ctx->failed = ctx->too_big = 1;
        return NULL;
    }
    if ((ctx->nodes & 255u) == 0 && monotonic_ms() >= ctx->plugin->deadline) {
        ctx->failed = ctx->timeout = 1;
        return NULL;
    }
    switch (lua_type(L, idx)) {
        case LUA_TNIL:
            return NULL;
        case LUA_TBOOLEAN:
            return (*env)->NewLocalRef(env, lua_toboolean(L, idx) ? J.bool_true : J.bool_false);
        case LUA_TNUMBER: {
            double d = lua_isinteger(L, idx) ? (double)lua_tointeger(L, idx) : (double)lua_tonumber(L, idx);
            jobject boxed = (*env)->CallStaticObjectMethod(env, J.double_, J.double_value_of, (jdouble)d);
            if (!boxed || (*env)->ExceptionCheck(env)) ctx->failed = 1;
            return boxed;
        }
        case LUA_TSTRING: {
            size_t len;
            const char *text = lua_tolstring(L, idx, &len);
            return jstring_from_lua(env, text, len, ctx);
        }
        case LUA_TFUNCTION:
            return function_to_java(L, env, idx, ctx);
        case LUA_TTABLE:
            return table_to_java(L, env, idx, depth, ctx);
        default: {   /* userdata, threads: the text tostring would give */
            size_t len;
            push_plain_text(L, idx);
            const char *text = lua_tolstring(L, -1, &len);
            jstring result = jstring_from_lua(env, text, len, ctx);
            lua_pop(L, 1);
            return result;
        }
    }
}

/* ------------------------------------------------------------------ Java -> Lua */

static void push_java(lua_State *L, JNIEnv *env, jobject obj, int depth);

static void push_number(lua_State *L, double d) {
    /* whole values become Lua integers, so 5 prints as 5 and not 5.0 (NaN fails both comparisons) */
    if (d >= -9223372036854775808.0 && d < 9223372036854775808.0 && d == floor(d)) {
        lua_pushinteger(L, (lua_Integer)d);
    } else {
        lua_pushnumber(L, (lua_Number)d);
    }
}

static int host_function(lua_State *L);

static void push_java(lua_State *L, JNIEnv *env, jobject obj, int depth) {
    if (!obj || depth > MAX_JAVA_DEPTH || !lua_checkstack(L, 8)) {
        lua_pushnil(L);
        return;
    }
    if ((*env)->IsInstanceOf(env, obj, J.string)) {
        push_jstring(L, env, (jstring)obj);
    } else if ((*env)->IsInstanceOf(env, obj, J.number)) {
        double d = (double)(*env)->CallDoubleMethod(env, obj, J.number_double_value);
        if ((*env)->ExceptionCheck(env)) lua_pushnil(L);
        else push_number(L, d);
    } else if ((*env)->IsInstanceOf(env, obj, J.boolean_)) {
        int b = (*env)->CallBooleanMethod(env, obj, J.boolean_value) ? 1 : 0;
        if ((*env)->ExceptionCheck(env)) lua_pushnil(L);
        else lua_pushboolean(L, b);
    } else if ((*env)->IsInstanceOf(env, obj, J.object_array)) {
        jobjectArray list = (jobjectArray)obj;
        jsize n = (*env)->GetArrayLength(env, list);
        lua_createtable(L, n, 0);
        for (jsize i = 0; i < n; i++) {
            jobject item = (*env)->GetObjectArrayElement(env, list, i);
            push_java(L, env, item, depth + 1);
            if (item) (*env)->DeleteLocalRef(env, item);
            lua_rawseti(L, -2, (lua_Integer)i + 1);   /* a nil leaves a hole, as before */
        }
    } else if ((*env)->IsInstanceOf(env, obj, J.map)) {
        jobjectArray flat = (jobjectArray)(*env)->GetObjectField(env, obj, J.map_flat);
        jsize n = flat ? (*env)->GetArrayLength(env, flat) : 0;
        lua_createtable(L, 0, n / 2);
        for (jsize i = 0; i + 1 < n; i += 2) {
            jobject key = (*env)->GetObjectArrayElement(env, flat, i);
            jobject value = (*env)->GetObjectArrayElement(env, flat, i + 1);
            push_jstring(L, env, (jstring)key);
            push_java(L, env, value, depth + 1);
            if (lua_isnil(L, -1)) lua_pop(L, 2);
            else lua_rawset(L, -3);
            if (key) (*env)->DeleteLocalRef(env, key);
            if (value) (*env)->DeleteLocalRef(env, value);
        }
        if (flat) (*env)->DeleteLocalRef(env, flat);
    } else if ((*env)->IsInstanceOf(env, obj, J.host_ref)) {
        jint id = (*env)->GetIntField(env, obj, J.host_id);
        jstring name = (jstring)(*env)->GetObjectField(env, obj, J.host_name);
        lua_pushinteger(L, id);
        push_jstring(L, env, name);
        if (name) (*env)->DeleteLocalRef(env, name);
        lua_pushcclosure(L, host_function, 2);
    } else if ((*env)->IsInstanceOf(env, obj, J.fn_ref)) {
        jint id = (*env)->GetIntField(env, obj, J.fn_id);
        lua_rawgeti(L, LUA_REGISTRYINDEX, plugin_of(L)->callbacks);
        lua_rawgeti(L, -1, id);   /* nil when the function is gone */
        lua_remove(L, -2);
    } else if ((*env)->IsInstanceOf(env, obj, J.json_text)) {
        jstring text = (jstring)(*env)->GetObjectField(env, obj, J.json_text_field);
        lua_rawgeti(L, LUA_REGISTRYINDEX, plugin_of(L)->bridge_json);
        push_jstring(L, env, text);
        if (text) (*env)->DeleteLocalRef(env, text);
        if (lua_pcall(L, 1, 1, 0) != LUA_OK) {   /* unparsable JSON is nil, as with kb.json.decode */
            lua_pop(L, 1);
            lua_pushnil(L);
        }
    } else {
        lua_pushnil(L);
    }
}

/* ------------------------------------------------------------------ calls from Lua into Kotlin */

/* A Kotlin function seen from Lua: upvalue 1 is its id, upvalue 2 its name. */
static int host_function(lua_State *L) {
    PluginRuntime *plugin = plugin_of(L);
    JNIEnv *env = plugin->env;
    lua_Integer id = lua_tointeger(L, lua_upvalueindex(1));
    int count = lua_gettop(L);
    /* an earlier host call left a Java exception pending (the script was stopped): never touch JNI again */
    if ((*env)->ExceptionCheck(env)) {
        stop_everything(plugin);
        return luaL_error(L, "script ran too long and was stopped");
    }

    Convert ctx = {plugin, DATA_LIMIT, 0, 0, 0, 0};
    jobjectArray args = (*env)->NewObjectArray(env, count, J.object, NULL);
    if (!args) ctx.failed = 1;
    for (int i = 1; i <= count && !ctx.failed; i++) {
        jobject item = java_from_lua(L, env, i, 0, &ctx);
        if (item) {
            (*env)->SetObjectArrayElement(env, args, i - 1, item);
            (*env)->DeleteLocalRef(env, item);
        }
        if ((*env)->ExceptionCheck(env)) ctx.failed = 1;
    }
    if (ctx.failed) {
        if (args) (*env)->DeleteLocalRef(env, args);
        if ((*env)->ExceptionCheck(env)) {
            stop_everything(plugin);
            return luaL_error(L, "Host call failed");
        }
        if (ctx.timeout) {
            stop_everything(plugin);
            return luaL_error(L, "script ran too long and was stopped");
        }
        if (ctx.too_big) return luaL_error(L, "Plugin data exceeds 16 MiB");
        return luaL_error(L, "not enough memory");
    }

    int64_t started = monotonic_ms();
    jobject result = (*env)->CallObjectMethod(env, plugin->owner, plugin->dispatch_host, (jint)id, args);
    (*env)->DeleteLocalRef(env, args);
    /* time spent inside the keyboard is not script time */
    plugin->deadline += monotonic_ms() - started;

    if ((*env)->ExceptionCheck(env)) {
        /* keep the Java exception pending (the Kotlin side rethrows it) and unwind the script */
        if (result) (*env)->DeleteLocalRef(env, result);
        stop_everything(plugin);
        return luaL_error(L, "Host call failed");
    }
    if (result && (*env)->IsInstanceOf(env, result, J.host_error)) {
        jstring message = (jstring)(*env)->GetObjectField(env, result, J.error_message);
        (*env)->DeleteLocalRef(env, result);
        if (message) {
            push_jstring(L, env, message);
            (*env)->DeleteLocalRef(env, message);
        } else {
            lua_pushfstring(L, "host call %s failed", lua_tostring(L, lua_upvalueindex(2)));
        }
        luaL_where(L, 1);   /* the position of the Lua code that made the call */
        lua_insert(L, -2);
        lua_concat(L, 2);
        return lua_error(L);
    }
    push_java(L, env, result, 0);
    if (result) (*env)->DeleteLocalRef(env, result);
    if ((*env)->ExceptionCheck(env)) {
        stop_everything(plugin);
        return luaL_error(L, "Host call failed");
    }
    return 1;
}

/* ------------------------------------------------------------------ calls from Kotlin into Lua */

static int message_handler(lua_State *L) {
    if (!lua_isstring(L, 1)) {
        lua_pushfstring(L, "(error object is a %s value)", luaL_typename(L, 1));
    }
    return 1;
}

typedef struct {
    jint id;
    jobjectArray args;
    jintArray expired;
    jboolean read_result;
    jobject result;      /* out: local reference to the converted result */
    jobject bindings;    /* install only */
} Frame;

/* lua_pcall target: calls Lua callback frame->id with the Java arguments. */
static int call_callback(lua_State *L) {
    Frame *frame = lua_touserdata(L, 1);
    PluginRuntime *plugin = plugin_of(L);
    JNIEnv *env = plugin->env;

    if (frame->expired) {
        jsize n = (*env)->GetArrayLength(env, frame->expired);
        jint *ids = n ? (*env)->GetIntArrayElements(env, frame->expired, NULL) : NULL;
        if (ids) {
            lua_rawgeti(L, LUA_REGISTRYINDEX, plugin->callbacks);
            for (jsize i = 0; i < n; i++) {
                lua_pushnil(L);
                lua_rawseti(L, -2, ids[i]);
            }
            lua_pop(L, 1);
            (*env)->ReleaseIntArrayElements(env, frame->expired, ids, JNI_ABORT);
        }
    }

    lua_rawgeti(L, LUA_REGISTRYINDEX, plugin->callbacks);
    lua_rawgeti(L, -1, frame->id);
    lua_remove(L, -2);
    if (!lua_isfunction(L, -1)) {
        lua_pushliteral(L, "the Lua callback is gone");
        return lua_error(L);
    }
    jsize n = frame->args ? (*env)->GetArrayLength(env, frame->args) : 0;
    luaL_checkstack(L, n + 8, "too many arguments");
    for (jsize i = 0; i < n; i++) {
        jobject item = (*env)->GetObjectArrayElement(env, frame->args, i);
        push_java(L, env, item, 0);
        if (item) (*env)->DeleteLocalRef(env, item);
    }
    if ((*env)->ExceptionCheck(env)) {
        stop_everything(plugin);
        return luaL_error(L, "Host call failed");
    }
    lua_call(L, n, frame->read_result ? 1 : 0);

    if (frame->read_result) {
        Convert ctx = {plugin, DATA_LIMIT, 0, 0, 0, 0};
        frame->result = java_from_lua(L, env, -1, 0, &ctx);
        if (ctx.failed) {
            if ((*env)->ExceptionCheck(env)) {
                stop_everything(plugin);
                return luaL_error(L, "Host call failed");
            }
            if (ctx.timeout) {
                stop_everything(plugin);
                return luaL_error(L, "script ran too long and was stopped");
            }
            if (ctx.too_big) return luaL_error(L, "Plugin data exceeds 16 MiB");
            return luaL_error(L, "not enough memory");
        }
    }
    return 0;
}

/* lua_pcall target: install(bindings) from the bridge. */
static int install_bindings(lua_State *L) {
    Frame *frame = lua_touserdata(L, 1);
    PluginRuntime *plugin = plugin_of(L);
    lua_rawgeti(L, LUA_REGISTRYINDEX, plugin->bridge_install);
    push_java(L, plugin->env, frame->bindings, 0);
    lua_call(L, 1, 0);
    return 0;
}

/* lua_pcall target: keeps the functions the bridge chunk returned. */
static int store_bridge(lua_State *L) {
    PluginRuntime *plugin = plugin_of(L);
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_getfield(L, 1, "install");
    plugin->bridge_install = luaL_ref(L, LUA_REGISTRYINDEX);
    lua_getfield(L, 1, "jsonDecode");
    plugin->bridge_json = luaL_ref(L, LUA_REGISTRYINDEX);
    lua_newtable(L);
    plugin->callbacks = luaL_ref(L, LUA_REGISTRYINDEX);
    return 0;
}

static const luaL_Reg safe_libs[] = {
    {LUA_GNAME, luaopen_base},
    {LUA_COLIBNAME, luaopen_coroutine},
    {LUA_TABLIBNAME, luaopen_table},
    {LUA_STRLIBNAME, luaopen_string},
    {LUA_UTF8LIBNAME, luaopen_utf8},
    {LUA_MATHLIBNAME, luaopen_math},
    {NULL, NULL}
};

static void open_safe_libs(lua_State *L) {
    for (const luaL_Reg *lib = safe_libs; lib->func; lib++) {
        luaL_requiref(L, lib->name, lib->func, 1);
        lua_pop(L, 1);
    }
    /* nothing that reads files or runs binary chunks */
    static const char *removed[] = {"dofile", "loadfile", "collectgarbage", NULL};
    for (int i = 0; removed[i]; i++) {
        lua_pushnil(L);
        lua_setglobal(L, removed[i]);
    }
    lua_getglobal(L, "string");
    lua_pushnil(L);
    lua_setfield(L, -2, "dump");
    lua_pop(L, 1);
}

JNIEXPORT jlong JNICALL
Java_com_expressive_keyboard_LuaPluginRuntime_nativeCreate(JNIEnv *env, jobject owner) {
    if (!jni_init(env)) return 0;
    jclass cls = (*env)->GetObjectClass(env, owner);
    jmethodID dispatch = (*env)->GetMethodID(env, cls, "dispatchHost", "(I[Ljava/lang/Object;)Ljava/lang/Object;");
    if (!dispatch) return 0;
    PluginRuntime *plugin = calloc(1, sizeof(*plugin));
    if (!plugin) return 0;
    plugin->L = lua_newstate(limited_alloc, plugin);
    if (!plugin->L) {
        free(plugin);
        return 0;
    }
    plugin->dispatch_host = dispatch;
    plugin->bridge_install = plugin->bridge_json = plugin->callbacks = LUA_NOREF;
    plugin->deadline = INT64_MAX;
    open_safe_libs(plugin->L);
    lua_sethook(plugin->L, deadline_hook, LUA_MASKCOUNT, HOOK_COUNT);
    return (jlong)(intptr_t)plugin;
}

static jbyteArray response_bytes(JNIEnv *env, int failed, const char *text, size_t length) {
    jbyteArray response = (*env)->NewByteArray(env, (jsize)length + 1);
    if (!response) return NULL;
    jbyte status = (jbyte)failed;
    (*env)->SetByteArrayRegion(env, response, 0, 1, &status);
    if (length) (*env)->SetByteArrayRegion(env, response, 1, (jsize)length, (const jbyte *)text);
    return response;
}

/* Runs a source chunk. On success leaves one value on the stack and returns LUA_OK. */
static int evaluate(PluginRuntime *plugin, JNIEnv *env, jint mode, jbyteArray code, jstring filename) {
    lua_State *L = plugin->L;
    lua_pushcfunction(L, message_handler);
    int handler = lua_gettop(L);

    jsize length = (*env)->GetArrayLength(env, code);
    jbyte *source = (*env)->GetByteArrayElements(env, code, NULL);
    if (!source) {
        lua_pushliteral(L, "not enough memory");
        return LUA_ERRMEM;
    }
    const char *name = (*env)->GetStringUTFChars(env, filename, NULL);
    char chunkname[256];
    chunkname[0] = '=';
    strncpy(chunkname + 1, name ? name : "plugin", sizeof(chunkname) - 2);
    chunkname[sizeof(chunkname) - 1] = '\0';
    if (name) (*env)->ReleaseStringUTFChars(env, filename, name);
    /* text chunks only: precompiled bytecode can crash the VM */
    int status = luaL_loadbufferx(L, (const char *)source, (size_t)length, chunkname, "t");
    if (status == LUA_OK) status = lua_pcall(L, 0, mode == BOOTSTRAP ? 1 : 0, handler);
    if (status == LUA_OK && mode == BOOTSTRAP) {
        lua_pushcfunction(L, store_bridge);
        lua_insert(L, -2);
        status = lua_pcall(L, 1, 0, handler);
    }
    if (status == LUA_OK) lua_pushliteral(L, "");
    (*env)->ReleaseByteArrayElements(env, code, source, JNI_ABORT);
    return status;
}

/* Deadline and nesting bookkeeping shared by every way into the VM. A call can nest: Lua calls a host function,
 * which calls back into a Lua callback. */
typedef struct {
    int nested;
    int64_t previous_deadline;
    int previous_interrupt;
    int top;
} Entry;

static void enter_run(PluginRuntime *plugin, JNIEnv *env, jobject owner, jlong budget, Entry *entry) {
    lua_State *L = plugin->L;
    entry->nested = plugin->depth++;
    entry->previous_deadline = plugin->deadline;
    entry->previous_interrupt = plugin->interrupted;
    entry->top = lua_gettop(L);
    plugin->deadline = monotonic_ms() + budget;
    if (!entry->nested) {
        plugin->env = env;
        plugin->owner = owner;
        plugin->interrupted = 0;
        lua_sethook(L, deadline_hook, LUA_MASKCOUNT, HOOK_COUNT);
    } else if (entry->previous_deadline < plugin->deadline) {
        plugin->deadline = entry->previous_deadline;
    }
}

static void leave_run(PluginRuntime *plugin, const Entry *entry) {
    lua_State *L = plugin->L;
    lua_settop(L, entry->top);
    plugin->depth--;
    int was_interrupted = plugin->interrupted;
    plugin->interrupted = entry->previous_interrupt;
    plugin->deadline = entry->previous_deadline;
    if (!entry->nested) {
        plugin->owner = NULL;
        plugin->env = NULL;
        /* leave the hook running every instruction only while a stop is in progress */
        lua_sethook(L, deadline_hook, LUA_MASKCOUNT, HOOK_COUNT);
    } else if (was_interrupted && !entry->previous_interrupt) {
        plugin->interrupted = 1;   /* a nested call that timed out stops the outer script too */
    }
}

/* Turns the Lua error message on top of the stack into a pending IllegalStateException. */
static void throw_lua_error(PluginRuntime *plugin, JNIEnv *env) {
    lua_State *L = plugin->L;
    size_t length = 0;
    const char *text = lua_tolstring(L, -1, &length);
    if (!text) {
        text = "Lua execution failed";
        length = strlen(text);
    }
    jstring message = make_jstring(env, text, length);
    if (!message) return;   /* out of memory: that error is already pending */
    jobject error = (*env)->NewObject(env, J.illegal_state, J.illegal_state_init, message);
    if (error) (*env)->Throw(env, (jthrowable)error);
    (*env)->DeleteLocalRef(env, message);
    if (error) (*env)->DeleteLocalRef(env, error);
}

JNIEXPORT jbyteArray JNICALL
Java_com_expressive_keyboard_LuaPluginRuntime_nativeRun(
        JNIEnv *env, jobject owner, jlong handle, jint mode, jbyteArray code, jstring filename, jlong budget) {
    PluginRuntime *plugin = (PluginRuntime *)(intptr_t)handle;
    lua_State *L = plugin->L;
    Entry entry;
    enter_run(plugin, env, owner, budget, &entry);

    int status = evaluate(plugin, env, mode, code, filename);
    int java_failed = (*env)->ExceptionCheck(env);
    jbyteArray response = NULL;

    if (!plugin->interrupted && !java_failed) {
        size_t length = 0;
        const char *text = lua_tolstring(L, -1, &length);   /* the result or the error message */
        if (status == LUA_OK) {
            response = response_bytes(env, 0, "", 0);
        } else {
            const char *message = text ? text : "Lua execution failed";
            response = response_bytes(env, 1, message, text ? length : strlen(message));
        }
    }
    leave_run(plugin, &entry);
    return response;
}

/* Hands the host bindings to the bridge, which makes them globals. Returns false when the script was stopped. */
JNIEXPORT jboolean JNICALL
Java_com_expressive_keyboard_LuaPluginRuntime_nativeInstall(
        JNIEnv *env, jobject owner, jlong handle, jobject bindings, jlong budget) {
    PluginRuntime *plugin = (PluginRuntime *)(intptr_t)handle;
    lua_State *L = plugin->L;
    Entry entry;
    enter_run(plugin, env, owner, budget, &entry);

    Frame frame;
    memset(&frame, 0, sizeof(frame));
    frame.bindings = bindings;
    lua_pushcfunction(L, message_handler);
    int handler = lua_gettop(L);
    lua_pushcfunction(L, install_bindings);
    lua_pushlightuserdata(L, &frame);
    int status = lua_pcall(L, 1, 0, handler);

    jboolean ok = JNI_FALSE;
    if (!plugin->interrupted && !(*env)->ExceptionCheck(env)) {
        if (status == LUA_OK) ok = JNI_TRUE;
        else throw_lua_error(plugin, env);
    }
    leave_run(plugin, &entry);
    return ok;
}

/* Calls a Lua callback. Returns a one-element array holding the result (null when it was not asked for or is nil),
 * or null when the script was stopped. A Lua error becomes an IllegalStateException. */
JNIEXPORT jobjectArray JNICALL
Java_com_expressive_keyboard_LuaPluginRuntime_nativeCall(
        JNIEnv *env, jobject owner, jlong handle, jint id, jobjectArray args, jintArray expired,
        jboolean read_result, jlong budget) {
    PluginRuntime *plugin = (PluginRuntime *)(intptr_t)handle;
    lua_State *L = plugin->L;
    Entry entry;
    enter_run(plugin, env, owner, budget, &entry);

    Frame frame;
    memset(&frame, 0, sizeof(frame));
    frame.id = id;
    frame.args = args;
    frame.expired = expired;
    frame.read_result = read_result;
    lua_pushcfunction(L, message_handler);
    int handler = lua_gettop(L);
    lua_pushcfunction(L, call_callback);
    lua_pushlightuserdata(L, &frame);
    int status = lua_pcall(L, 1, 0, handler);

    jobjectArray response = NULL;
    if (!plugin->interrupted && !(*env)->ExceptionCheck(env)) {
        if (status == LUA_OK) {
            response = (*env)->NewObjectArray(env, 1, J.object, NULL);
            if (response && frame.result) (*env)->SetObjectArrayElement(env, response, 0, frame.result);
        } else {
            throw_lua_error(plugin, env);
        }
    }
    if (frame.result) (*env)->DeleteLocalRef(env, frame.result);
    leave_run(plugin, &entry);
    return response;
}

JNIEXPORT void JNICALL
Java_com_expressive_keyboard_LuaPluginRuntime_nativeClose(JNIEnv *env, jobject owner, jlong handle) {
    (void)env;
    (void)owner;
    PluginRuntime *plugin = (PluginRuntime *)(intptr_t)handle;
    lua_close(plugin->L);
    free(plugin);
}
