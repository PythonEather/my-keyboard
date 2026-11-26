package com.expressive.keyboard

import android.content.res.AssetManager
import android.os.SystemClock
import org.mozilla.javascript.Undefined
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

// Values cross the Lua boundary as plain Java objects (plugin_lua.c converts them with JNI, no JSON text):
//   null, String, Boolean, Double      as they are
//   Array<Any?>                        a list
//   LuaMap                             a table: key0, value0, key1, value1, ...
//   LuaHostRef                         a keyboard function the script can call
//   LuaFnRef                           a Lua function, called back by id
//   LuaJsonText                        JSON text that Lua parses into plain tables
//   LuaHostError                       a keyboard function that threw (returned to Lua, which raises the error)
// The native code looks these classes and their fields up by name: keep them in proguard-rules.pro.
internal class LuaMap(@JvmField val flat: Array<Any?>)
internal class LuaHostRef(@JvmField val id: Int, @JvmField val name: String)
internal class LuaFnRef(@JvmField val id: Int)
internal class LuaJsonText(@JvmField val text: String)
internal class LuaHostError(@JvmField val message: String)

internal class LuaPluginRuntime(private val assets: AssetManager) : PluginScriptRuntime {
    private var handle = nativeCreate().also { check(it != 0L) { "Could not create Lua runtime" } }
    private var deadline = Long.MAX_VALUE
    private var closePending = false
    private val bindings = ArrayList<ScriptBinding>()
    private val collectedFunctions = ReferenceQueue<Callback>()
    private val callbacks = HashMap<Int, CallbackRef>()

    private inner class Callback(val id: Int) : PluginScriptFunction {
        fun belongsTo(runtime: LuaPluginRuntime) = this@LuaPluginRuntime === runtime
    }

    private class CallbackRef(val id: Int, callback: Callback, queue: ReferenceQueue<Callback>) :
        WeakReference<Callback>(callback, queue)

    override fun load(bindings: Map<String, Any?>, prelude: String, source: String, filename: String) {
        val bootstrap = assets.open("lua/plugin-bridge.lua").bufferedReader().use { it.readText() }
        run(BOOTSTRAP, bootstrap, "lua-bridge.lua")
        install(encode(bindings))
        // the JavaScript prelude passed in is for JS engines: a Lua plugin gets its own helpers
        val luaPrelude = assets.open("lua/prelude.lua").bufferedReader().use { it.readText() }
        run(EVAL, luaPrelude, "kb-prelude.lua")
        run(EVAL, source, filename)
    }

    override fun call(function: PluginScriptFunction, args: Array<out Any?>, readResult: Boolean): Any? {
        val callback = function as Callback
        check(callback.belongsTo(this))
        val expired = ArrayList<Int>()
        while (true) {
            val ref = collectedFunctions.poll() as? CallbackRef ?: break
            if (callbacks[ref.id] === ref) {
                callbacks.remove(ref.id)
                expired.add(ref.id)
            }
        }
        val encoded = Array(args.size) { encode(args[it]) }
        val remaining = remainingBudget()
        val response = nativeCall(
            handle, callback.id, encoded, if (expired.isEmpty()) null else expired.toIntArray(), readResult, remaining,
        ) ?: throw ScriptTimeout()
        return decode(response[0])
    }

    override fun <T> execute(budgetMs: Long, block: () -> T): T {
        check(handle != 0L && !closePending) { "Lua runtime is closed" }
        val previous = deadline
        deadline = minOf(previous, SystemClock.uptimeMillis() + budgetMs)
        return try {
            block()
        } finally {
            deadline = previous
            if (previous == Long.MAX_VALUE && closePending) close()
        }
    }

    override fun close() {
        if (deadline != Long.MAX_VALUE) {
            closePending = true
            return
        }
        if (handle != 0L) nativeClose(handle)
        handle = 0
        bindings.clear()
        callbacks.clear()
    }

    private fun remainingBudget(): Long {
        val remaining = deadline - SystemClock.uptimeMillis()
        if (remaining <= 0) throw ScriptTimeout()
        return remaining
    }

    private fun install(encodedBindings: Any?) {
        if (!nativeInstall(handle, encodedBindings, remainingBudget())) throw ScriptTimeout()
    }

    private fun run(mode: Int, source: String, filename: String): String {
        val response = nativeRun(handle, mode, source.toByteArray(), filename, remainingBudget()) ?: throw ScriptTimeout()
        val result = response.decodeToString(startIndex = 1)
        if (response[0].toInt() != 0) throw IllegalStateException(result)
        return result
    }

    @Suppress("unused") // Called synchronously by the native runtime on the keyboard thread.
    private fun dispatchHost(id: Int, args: Array<Any?>): Any? {
        return try {
            val values = Array(args.size) { decode(args[it]) }
            encode(bindings[id].invoke(values))
        } catch (timeout: ScriptTimeout) {
            throw timeout
        } catch (error: Exception) {
            LuaHostError(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun encode(value: Any?, depth: Int = 0): Any? = when {
        value == null || depth > 32 -> null
        value is Unit || value is Undefined -> null
        value is String || value is Boolean -> value
        value is Number -> value.toDouble()
        value is ScriptBinding -> LuaHostRef(bindings.size.also { bindings += value }, value.name)
        value is ScriptJson -> LuaJsonText(value.source)
        value is Callback && value.belongsTo(this) -> LuaFnRef(value.id)
        value is PluginScriptFunction -> null
        value is List<*> -> Array(value.size) { encode(value[it], depth + 1) }
        value is Map<*, *> -> {
            val flat = arrayOfNulls<Any?>(value.size * 2)
            var i = 0
            for ((key, item) in value) {
                flat[i++] = key.toString()
                flat[i++] = encode(item, depth + 1)
            }
            LuaMap(flat)
        }
        else -> value.toString()
    }

    private fun decode(value: Any?): Any? = when (value) {
        null -> null
        is Double, is String, is Boolean -> value
        is LuaFnRef -> {
            val id = value.id
            callbacks[id]?.get() ?: Callback(id).also { callbacks[id] = CallbackRef(id, it, collectedFunctions) }
        }
        is Array<*> -> List(value.size) { decode(value[it]) }
        is LuaMap -> {
            val flat = value.flat
            val map = LinkedHashMap<String, Any?>()
            var i = 0
            while (i + 1 < flat.size) {
                map[flat[i] as String] = decode(flat[i + 1])
                i += 2
            }
            map
        }
        else -> error("Unknown Lua value")
    }

    private external fun nativeCreate(): Long
    private external fun nativeRun(handle: Long, mode: Int, code: ByteArray, filename: String, budgetMs: Long): ByteArray?
    private external fun nativeInstall(handle: Long, bindings: Any?, budgetMs: Long): Boolean
    private external fun nativeCall(
        handle: Long, id: Int, args: Array<Any?>, expired: IntArray?, readResult: Boolean, budgetMs: Long,
    ): Array<Any?>?
    private external fun nativeClose(handle: Long)

    private companion object {
        const val BOOTSTRAP = 0
        const val EVAL = 1

        init {
            System.loadLibrary("materialboard_lua")
        }
    }
}
