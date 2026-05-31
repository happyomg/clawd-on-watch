package com.clawd.watch.domain

import android.util.Log
import java.io.File

/**
 * Bounds the on-disk theme cache (filesDir/themes/<hash>/) by keeping only the
 * themes worth retaining — typically the current theme, the previous one (cheap
 * to switch back to), and the bundled fallback — and deleting the rest along
 * with their recorded frames.
 */
object ThemeCache {

    private const val TAG = "ThemeCache"

    /**
     * Pure selection: of [existing] theme-hash directory names, which should be
     * deleted given the [keep] set. Blank keep entries are ignored.
     */
    fun selectForDeletion(existing: List<String>, keep: Set<String>): List<String> {
        val keepers = keep.filter { it.isNotBlank() }.toSet()
        return existing.filter { it.isNotBlank() && it !in keepers }
    }

    /** Delete every theme dir under [themesRoot] not in [keep]. */
    fun cleanup(themesRoot: File, keep: Set<String>) {
        val dirs = themesRoot.listFiles { f -> f.isDirectory }?.map { it.name } ?: return
        for (name in selectForDeletion(dirs, keep)) {
            val ok = File(themesRoot, name).deleteRecursively()
            Log.i(TAG, "evicted theme cache $name (ok=$ok)")
        }
    }
}
