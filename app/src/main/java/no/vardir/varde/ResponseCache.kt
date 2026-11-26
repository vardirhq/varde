package no.vardir.varde

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A saved GitHub response. [etag] is blank when GitHub sent none. */
internal data class CacheEntry(val body: String, val savedAt: Long, val etag: String)

/**
 * GitHub responses on disk, one file per request, capped at [maxBytes] with the least recently used files evicted first.
 * Keys are hashed, so file names reveal nothing about repositories. Lives in the app's cache directory, which Android may
 * clear when storage is low: it's an offline convenience, never the only copy of anything. Cleared on sign-out.
 */
internal class ResponseCache(private val dir: File, private val maxBytes: Long) {
    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, hash)
    }

    @Synchronized fun get(key: String): CacheEntry? {
        val f = file(key)
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            // Keys are stored too, so a hash collision can never return another request's data.
            if (o.optString("key") != key) return null
            f.setLastModified(System.currentTimeMillis())
            CacheEntry(o.getString("body"), o.optLong("savedAt"), o.optString("etag"))
        } catch (_: Exception) { f.delete(); null }
    }

    @Synchronized fun put(key: String, body: String, etag: String = "", savedAt: Long = System.currentTimeMillis()) {
        // A single huge response would evict everything else; it's simply not saved.
        if (body.length > maxBytes / 4) return
        try {
            dir.mkdirs()
            val target = file(key)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(JSONObject().put("key", key).put("body", body).put("savedAt", savedAt).put("etag", etag).toString())
            if (!temp.renameTo(target)) { temp.delete(); return }
            trim(keep = target)
        } catch (_: Exception) { /* A cache that can't be written is just a cache miss later. */ }
    }

    /** Evicts least recently used files until well under the cap. [keep], the entry just written, is never evicted. */
    private fun trim(keep: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        // Files written within one clock tick share a timestamp, so the name breaks ties and [keep] is skipped.
        for (f in files.filter { it != keep }.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
            if (total <= maxBytes * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    @Synchronized fun clear() { dir.deleteRecursively() }

    companion object {
        private var workspace: ResponseCache? = null
        private var conditional: ResponseCache? = null

        /** Screens' offline copies. */
        @Synchronized fun workspace(context: android.content.Context): ResponseCache =
            workspace ?: ResponseCache(File(context.cacheDir, "github/workspace"), 30L * 1024 * 1024).also { workspace = it }

        /** ETag-validated copies for the inbox feed and background checks. */
        @Synchronized fun conditional(context: android.content.Context): ResponseCache =
            conditional ?: ResponseCache(File(context.cacheDir, "github/conditional"), 15L * 1024 * 1024).also { conditional = it }

        /** Removes every saved response, including the stores older versions kept in preferences. */
        fun clearAll(context: android.content.Context) {
            workspace(context).clear(); conditional(context).clear()
            removeLegacy(context)
        }

        fun removeLegacy(context: android.content.Context) {
            listOf("workspace_cache", "background_cache").forEach { context.deleteSharedPreferences(it) }
        }
    }
}
