package com.expressive.keyboard

import java.util.concurrent.ConcurrentHashMap

/*
 * The part of the built-in 3D engine that sits between a mod and the GL thread: the live state of every scene (what
 * kb.scene(id, patch) changes), which files of the plugin a scene may read, and the background used when a scene layer
 * has no shader of its own. No Android classes here, so it is unit-tested on a plain JVM.
 */

object SceneHost {
    /** A scene layer without a `frag` gets this. The renderer recognises it and draws no background at all (the layer's `clear` colour shows). */
    const val BACKGROUND_MARK = "// scene: no background shader"
    const val BACKGROUND_FRAG = BACKGROUND_MARK + "\nprecision mediump float;\nvoid main() { discard; }\n"

    fun isDefaultBackground(frag: String): Boolean = frag.startsWith(BACKGROUND_MARK)
}

/** What the GL thread drew its last frame from: the same snapshot is reused until a patch arrives. */
internal class SceneSnapshot(val owner: Any?, val version: Int, val state: SceneState)

/** The live scenes, by `<plugin id>/<layer id>`. A new kb.overlay starts every scene again from its description. */
internal object SceneStore {
    private class Live(val state: SceneState) {
        var version = 0
    }

    private const val MAX = 64
    private val map = ConcurrentHashMap<String, Live>()

    fun reset(key: String, spec: SceneSpec) {
        if (map.size >= MAX && !map.containsKey(key)) map.clear()
        map[key] = Live(spec.initial.copy())
    }

    /**
     * Applies a mod's patch. Returns the pictures the objects name afterwards (so the host can make their files known to the
     * GL thread), or null when no scene has this key.
     */
    fun patch(key: String, patch: Map<*, *>): List<String>? {
        val live = map[key] ?: return null
        synchronized(live) {
            live.state.apply(patch)
            live.version++
            return live.state.textureSources()
        }
    }

    fun drop(key: String) {
        map.remove(key)
    }

    fun has(key: String): Boolean = map.containsKey(key)

    /** The state to draw now. [prev] is returned unchanged when nothing happened since it was taken. */
    fun read(key: String?, spec: SceneSpec, prev: SceneSnapshot?): SceneSnapshot {
        val live = if (key == null) null else map[key]
        if (live == null) {
            if (prev != null && prev.owner == null && prev.state === spec.initial) return prev
            return SceneSnapshot(null, 0, spec.initial)
        }
        synchronized(live) {
            if (prev != null && prev.owner === live && prev.version == live.version) return prev
            return SceneSnapshot(live, live.version, live.state.copy())
        }
    }
}

object SceneFiles {
    private val EXT = setOf("png", "jpg", "jpeg", "webp", "mtl", "bin", "obj", "gltf", "glb")

    /** At most this many of a plugin's files are made known to the GL thread for its scenes. */
    const val MAX_FILES = 512

    /**
     * The plugin files a scene might read: models, their .mtl and .bin files and their pictures. A model names its pictures
     * relative to itself, so every such file of the plugin is made known, wherever it lies. Making a file known copies nothing.
     */
    fun readable(allFiles: Collection<String>): List<String> =
        allFiles.filter { it.substringAfterLast('.', "").lowercase() in EXT }.sorted().take(MAX_FILES)

    /** Model files the scene lists that the plugin does not have. */
    fun missingModels(spec: SceneSpec, allFiles: Collection<String>): List<String> {
        val have = allFiles.toHashSet()
        return spec.modelFiles().filter { it !in have }
    }

    /** Pictures named by objects that are files of the plugin or `user:` pictures (a `data:` URI needs no registering). */
    fun named(sources: Collection<String>): List<String> = sources.filter { !it.startsWith("data:") }
}
