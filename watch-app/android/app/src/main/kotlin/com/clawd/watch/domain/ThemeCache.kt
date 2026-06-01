package com.clawd.watch.domain

import android.util.Log
import java.io.File

/**
 * Bounds the on-disk theme cache (filesDir/themes/<hash>/) using LRU eviction.
 * Keeps up to MAX_CACHED themes, evicting the least-recently-used first.
 * Uses directory lastModified as the LRU timestamp (updated on activate).
 */
object ThemeCache {

    private const val TAG = "ThemeCache"
    private const val MAX_CACHED = 5

    /**
     * Pure selection: of [existing] theme-hash directory names, which should be
     * deleted given the [keep] set. Blank keep entries are ignored.
     */
    fun selectForDeletion(existing: List<String>, keep: Set<String>): List<String> {
        val keepers = keep.filter { it.isNotBlank() }.toSet()
        return existing.filter { it.isNotBlank() && it !in keepers }
    }

    /**
     * LRU cleanup: keep [protect] set unconditionally, then keep up to
     * MAX_CACHED total by lastModified. Evict the rest.
     */
    fun cleanup(themesRoot: File, protect: Set<String>) {
        val dirs = themesRoot.listFiles { f -> f.isDirectory } ?: return
        if (dirs.size <= MAX_CACHED) return
        val protectSet = protect.filter { it.isNotBlank() }.toSet()
        val evictable = dirs.filter { it.name !in protectSet }
            .sortedBy { it.lastModified() }
        val toEvict = evictable.size - (MAX_CACHED - protectSet.size).coerceAtLeast(0)
        if (toEvict <= 0) return
        for (dir in evictable.take(toEvict)) {
            val ok = dir.deleteRecursively()
            Log.i(TAG, "evicted theme cache ${dir.name} (ok=$ok)")
        }
    }

    /** Touch the theme directory to mark it as recently used. */
    fun touch(themesRoot: File, hash: String) {
        val dir = File(themesRoot, hash)
        if (dir.isDirectory) dir.setLastModified(System.currentTimeMillis())
    }

    /** List all valid cached theme hashes (directories containing manifest.json). */
    fun listCachedHashes(themesRoot: File): List<String> {
        if (!themesRoot.isDirectory) return emptyList()
        return themesRoot.listFiles { f -> f.isDirectory && File(f, "manifest.json").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    /** Total byte size of a theme directory (all files recursively). */
    fun cacheSize(themeDir: File): Long {
        if (!themeDir.isDirectory) return 0L
        return themeDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Count recorded frame files (webp) under a theme's frames/ dir. */
    fun frameCount(themeDir: File): Int {
        val framesDir = File(themeDir, "frames")
        if (!framesDir.isDirectory) return 0
        return framesDir.walkTopDown().filter { it.isFile && it.extension == "webp" }.count()
    }

    /** Count how many states have been recorded (meta.json files under frames/). */
    fun recordedStateCount(themeDir: File): Int {
        val framesDir = File(themeDir, "frames")
        if (!framesDir.isDirectory) return 0
        return framesDir.listFiles { f -> f.isDirectory && File(f, "meta.json").exists() }?.size ?: 0
    }

    /** Delete only recorded frames, keep manifest.json and svg/ source files. */
    fun clearFrames(themeDir: File) {
        val framesDir = File(themeDir, "frames")
        if (framesDir.isDirectory) {
            framesDir.deleteRecursively()
            Log.i(TAG, "cleared frames for ${themeDir.name}")
        }
    }

    /** Delete entire theme directory. Returns false if dir doesn't exist. */
    fun deleteTheme(themeDir: File): Boolean {
        if (!themeDir.isDirectory) return false
        val ok = themeDir.deleteRecursively()
        Log.i(TAG, "deleted theme ${themeDir.name} (ok=$ok)")
        return ok
    }
}
