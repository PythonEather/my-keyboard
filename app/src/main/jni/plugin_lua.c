#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "lua/lua.h"
#include "lua/lauxlib.h"
#include "lua/lualib.h"

/*
 * Lua 5.4 for plugins. Same contract as plugin_quickjs.c: the Kotlin side hands over source text and gets
 * [status byte][text] back; everything crossing the boundary is JSON written and read by assets/lua/plugin-bridge.lua.
 *
 * Safety: only base/coroutine/table/string/utf8/math are opened (no io, os, debug, package), memory is capped,
 * and a count hook stops scripts that run past the deadline. After a stop the hook fires on EVERY instruction, so a
 * script cannot keep running by wrapping the endless loop in pcall.
 */

enum RunMode { BOOTSTRAP, EVAL, DISPATCH };

#define MEMORY_LIMIT (64u * 1024u * 1024u)
#define HOOK_COUNT 10000

typedef struct {
    lua_State *L;
    JNIEnv *env;
    jobject owner;
    jmethodID dispatch_host;
    int64_t deadline;
    int depth;
    int interrupted;
    int dispatcher;   /* registry reference of the bridge's dispatch function */
    size_t used;
} PluginRuntime;

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

/* __materialBoardHost(id, json) -> reply json */
static int host_call(lua_State *L) {
    PluginRuntime *plugin = plugin_of(L);
    JNIEnv *env = plugin->env;
    lua_Integer id = luaL_checkinteger(L, 1);
    size_t length;
    const char *json = luaL_checklstring(L, 2, &length);
    if (length > 16u * 1024u * 1024u) return luaL_error(L, "Plugin data exceeds 16 MiB");
    /* an earlier host call left a Java exception pending (the script was stopped): never touch JNI again */
    if ((*env)->ExceptionCheck(env)) {
        stop_everything(plugin);
        return luaL_error(L, "script ran too long and was stopped");
    }

    int64_t started = monotonic_ms();
    jbyteArray payload = (*env)->NewByteArray(env, (jsize)length);
    if (payload) (*env)->SetByteArrayRegion(env, payload, 0, (jsize)length, (const jbyte *)json);
    jbyteArray response = NULL;
    if (payload && !(*env)->ExceptionCheck(env)) {
        response = (*env)->CallObjectMethod(env, plugin->owner, plugin->dispatch_host, (jint)id, payload);
    }
    if (payload) (*env)->DeleteLocalRef(env, payload);
    /* time spent inside the keyboard is not script time */
    plugin->deadline += monotonic_ms() - started;

    if (!response || (*env)->ExceptionCheck(env)) {
        /* keep the Java exception pending (the Kotlin side rethrows it) and unwind the script */
        stop_everything(plugin);
        return luaL_error(L, "Host call failed");
    }
    jsize size = (*env)->GetArrayLength(env, response);
    jbyte *data = (*env)->GetByteArrayElements(env, response, NULL);
    if (!data) {
        (*env)->DeleteLocalRef(env, response);
        return luaL_error(L, "not enough memory");
    }
    lua_pushlstring(L, (const char *)data, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, response, data, JNI_ABORT);
    (*env)->DeleteLocalRef(env, response);
    return 1;
}

static int message_handler(lua_State *L) {
    if (!lua_isstring(L, 1)) {
        lua_pushfstring(L, "(error object is a %s value)", luaL_typename(L, 1));
    }
    return 1;
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
    jclass cls = (*env)->GetObjectClass(env, owner);
    jmethodID dispatch = (*env)->GetMethodID(env, cls, "dispatchHost", "(I[B)[B");
    if (!dispatch) return 0;
    PluginRuntime *plugin = calloc(1, sizeof(*plugin));
    if (!plugin) return 0;
    plugin->L = lua_newstate(limited_alloc, plugin);
    if (!plugin->L) {
        free(plugin);
        return 0;
    }
    plugin->dispatch_host = dispatch;
    plugin->dispatcher = LUA_NOREF;
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

/* Runs the chunk or the dispatcher. On success leaves one value on the stack and returns LUA_OK. */
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
    int status;
    if (mode == DISPATCH) {
        lua_rawgeti(L, LUA_REGISTRYINDEX, plugin->dispatcher);
        lua_pushlstring(L, (const char *)source, (size_t)length);
        status = lua_pcall(L, 1, 1, handler);
    } else {
        if (mode == BOOTSTRAP) {
            lua_pushcfunction(L, host_call);
            lua_setglobal(L, "__materialBoardHost");
        }
        const char *name = (*env)->GetStringUTFChars(env, filename, NULL);
        char chunkname[256];
        chunkname[0] = '=';
        strncpy(chunkname + 1, name ? name : "plugin", sizeof(chunkname) - 2);
        chunkname[sizeof(chunkname) - 1] = '\0';
        if (name) (*env)->ReleaseStringUTFChars(env, filename, name);
        /* text chunks only: precompiled bytecode can crash the VM */
        status = luaL_loadbufferx(L, (const char *)source, (size_t)length, chunkname, "t");
        if (status == LUA_OK) status = lua_pcall(L, 0, mode == BOOTSTRAP ? 1 : 0, handler);
        if (status == LUA_OK && mode != BOOTSTRAP) lua_pushliteral(L, "");
    }
    (*env)->ReleaseByteArrayElements(env, code, source, JNI_ABORT);
    return status;
}

JNIEXPORT jbyteArray JNICALL
Java_com_expressive_keyboard_LuaPluginRuntime_nativeRun(
        JNIEnv *env, jobject owner, jlong handle, jint mode, jbyteArray code, jstring filename, jlong budget) {
    PluginRuntime *plugin = (PluginRuntime *)(intptr_t)handle;
    lua_State *L = plugin->L;
    int nested = plugin->depth++;
    int64_t previous_deadline = plugin->deadline;
    int previous_interrupt = plugin->interrupted;
    int top = lua_gettop(L);
    plugin->deadline = monotonic_ms() + budget;
    if (!nested) {
        plugin->env = env;
        plugin->owner = owner;
        plugin->interrupted = 0;
        lua_sethook(L, deadline_hook, LUA_MASKCOUNT, HOOK_COUNT);
    } else if (previous_deadline < plugin->deadline) {
        plugin->deadline = previous_deadline;
    }

    int status = evaluate(plugin, env, mode, code, filename);
    int java_failed = (*env)->ExceptionCheck(env);
    jbyteArray response = NULL;

    if (!plugin->interrupted && !java_failed) {
        size_t length = 0;
        const char *text = lua_tolstring(L, -1, &length);   /* the result or the error message */
        if (status == LUA_OK) {
            if (mode == BOOTSTRAP) {
                lua_pushvalue(L, -1);
                plugin->dispatcher = luaL_ref(L, LUA_REGISTRYINDEX);
                response = response_bytes(env, 0, "", 0);
            } else {
                response = response_bytes(env, 0, mode == DISPATCH && text ? text : "", mode == DISPATCH && text ? length : 0);
            }
        } else {
            const char *message = text ? text : "Lua execution failed";
            response = response_bytes(env, 1, message, text ? length : strlen(message));
        }
    }

    lua_settop(L, top);
    plugin->depth--;
    int was_interrupted = plugin->interrupted;
    plugin->interrupted = previous_interrupt;
    plugin->deadline = previous_deadline;
    if (!nested) {
        plugin->owner = NULL;
        plugin->env = NULL;
        /* leave the hook running every instruction only while a stop is in progress */
        lua_sethook(L, deadline_hook, LUA_MASKCOUNT, HOOK_COUNT);
    } else if (was_interrupted && !previous_interrupt) {
        plugin->interrupted = 1;   /* a nested call that timed out stops the outer script too */
    }
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
