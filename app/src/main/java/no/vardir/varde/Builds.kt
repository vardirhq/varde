package no.vardir.varde

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** A workflow artifact that may hold an app build. GitHub doesn't list what's inside, so it's checked after download. */
internal data class BuildArtifact(
    val id: Long, val name: String, val sizeBytes: Long, val expired: Boolean, val createdAt: String,
    val runId: Long, val branch: String, val headSha: String,
)

/** An APK attached to a GitHub release. */
internal data class ReleaseApk(val assetId: Long, val name: String, val sizeBytes: Long, val tag: String, val createdAt: String)

/** Something installable and where to download it. Artifacts download as a zip that holds the APK. */
internal sealed interface BuildSource {
    val title: String
    val sizeBytes: Long
    val downloadPath: String
    val accept: String
    data class Artifact(val repo: Repo, val artifact: BuildArtifact) : BuildSource {
        override val title get() = artifact.name
        override val sizeBytes get() = artifact.sizeBytes
        override val downloadPath get() = repoApi(repo) + "/actions/artifacts/${artifact.id}/zip"
        override val accept get() = "application/vnd.github+json"
    }
    data class Asset(val repo: Repo, val apk: ReleaseApk) : BuildSource {
        override val title get() = apk.name
        override val sizeBytes get() = apk.sizeBytes
        override val downloadPath get() = repoApi(repo) + "/releases/assets/${apk.assetId}"
        override val accept get() = "application/octet-stream"
    }
}

internal fun parseArtifacts(body: String): List<BuildArtifact> {
    val array = JSONObject(body).optJSONArray("artifacts") ?: JSONArray()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { o ->
        val run = o.optJSONObject("workflow_run")
        BuildArtifact(o.optLong("id"), jsonText(o, "name"), o.optLong("size_in_bytes"), o.optBoolean("expired"), jsonText(o, "created_at"),
            run?.optLong("id") ?: 0, run?.let { jsonText(it, "head_branch") }.orEmpty(), run?.let { jsonText(it, "head_sha") }.orEmpty())
    }
}

/** APKs attached to a release response (GET /releases/latest or one item of /releases). */
internal fun releaseApks(release: JSONObject): List<ReleaseApk> {
    val assets = release.optJSONArray("assets") ?: JSONArray()
    val tag = jsonText(release, "tag_name")
    return (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        .filter { jsonText(it, "name").endsWith(".apk", ignoreCase = true) }
        .map { ReleaseApk(it.optLong("id"), jsonText(it, "name"), it.optLong("size"), tag, jsonText(it, "created_at")) }
}

private val AppLikeName = Regex("apk|android|debug|release|app|build|mobile", RegexOption.IGNORE_CASE)
private val NotAppName = Regex("test|report|coverage|lint|log|sarif|mapping|symbols", RegexOption.IGNORE_CASE)

/** Whether an artifact's name suggests an app build, or it held one before ([known] names). */
internal fun looksLikeBuild(artifact: BuildArtifact, known: Set<String>): Boolean =
    artifact.name in known || (AppLikeName.containsMatchIn(artifact.name) && !NotAppName.containsMatchIn(artifact.name))

/**
 * The builds worth offering: unexpired, likely app builds (unless [all]), the newest per artifact name and branch,
 * the default branch first. With [headSha], only builds of that commit (a pull request's head).
 */
internal fun buildCandidates(
    artifacts: List<BuildArtifact>, defaultBranch: String, known: Set<String>, all: Boolean = false, headSha: String? = null,
): List<BuildArtifact> = artifacts
    .filter { !it.expired && it.sizeBytes > 0 && (all || looksLikeBuild(it, known)) && (headSha == null || it.headSha == headSha) }
    .sortedByDescending { it.createdAt }
    .distinctBy { it.name to it.branch }
    .sortedWith(compareBy<BuildArtifact> { it.branch != defaultBranch }.thenByDescending { it.createdAt })

/**
 * The APK to install from a zip's entries: never test APKs; a universal APK over split ones; otherwise the first.
 * Returns null when there's no APK.
 */
internal fun pickApk(entries: List<String>): String? {
    val apks = entries.filter { it.endsWith(".apk", ignoreCase = true) && !it.endsWith("/") }
        .filterNot { Regex("androidTest|-test|_test", RegexOption.IGNORE_CASE).containsMatchIn(it.substringAfterLast('/')) }
    return apks.firstOrNull { it.contains("universal", ignoreCase = true) } ?: apks.minByOrNull { it.count { c -> c == '/' } }
}

/** Copies the chosen APK out of [zip] into [dest]; returns its name inside the zip, or null when there is no APK. */
internal fun extractApk(zip: File, dest: File, maxBytes: Long = 500_000_000): String? {
    val names = ZipInputStream(zip.inputStream().buffered()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
    val chosen = pickApk(names) ?: return null
    ZipInputStream(zip.inputStream().buffered()).use { z ->
        while (true) {
            val entry = z.nextEntry ?: break
            if (entry.name != chosen) continue
            // The entry's name is never used as a path, so a crafted zip can't write outside [dest].
            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = z.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= maxBytes) { "The APK inside is too large." }
                    out.write(buffer, 0, read)
                }
            }
            return chosen
        }
    }
    return null
}

internal fun megabytes(bytes: Long): String = if (bytes < 1_000_000) "${(bytes / 1000).coerceAtLeast(1)} KB" else "%.1f MB".format(bytes / 1_000_000.0)
