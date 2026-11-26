package no.vardir.varde

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuildsTest {
    private fun artifact(id: Long, name: String, branch: String, created: String, sha: String = "abc", expired: Boolean = false) =
        BuildArtifact(id, name, 1000, expired, created, id * 10, branch, sha)

    @Test fun parsesArtifactsWithTheirRuns() {
        val list = parseArtifacts("""{"artifacts":[{"id":7,"name":"varde-debug","size_in_bytes":14000000,"expired":false,"created_at":"2026-11-26T10:00:00Z",
            "workflow_run":{"id":70,"head_branch":"main","head_sha":"deadbeef"}}]}""")
        assertEquals(listOf(BuildArtifact(7, "varde-debug", 14000000, false, "2026-11-26T10:00:00Z", 70, "main", "deadbeef")), list)
    }

    @Test fun offersTheNewestAppBuildPerBranchWithTheDefaultBranchFirst() {
        val artifacts = listOf(
            artifact(1, "varde-debug", "main", "2026-11-20T00:00:00Z"),
            artifact(2, "varde-debug", "main", "2026-11-25T00:00:00Z"),
            artifact(3, "varde-debug", "feature", "2026-11-26T00:00:00Z"),
            artifact(4, "test-results", "main", "2026-11-26T00:00:00Z"),
            artifact(5, "apk", "main", "2026-11-26T00:00:00Z", expired = true),
        )
        assertEquals(listOf(2L, 3L), buildCandidates(artifacts, "main", emptySet()).map { it.id })
        assertEquals(listOf(4L, 2L, 3L), buildCandidates(artifacts, "main", emptySet(), all = true).map { it.id })
    }

    @Test fun rememberedNamesAreOfferedEvenWhenUnusual() {
        val a = artifact(1, "outputs", "main", "2026-11-26T00:00:00Z")
        assertTrue(buildCandidates(listOf(a), "main", emptySet()).isEmpty())
        assertEquals(listOf(a), buildCandidates(listOf(a), "main", setOf("outputs")))
    }

    @Test fun pullRequestBuildsMatchTheHeadCommit() {
        val artifacts = listOf(artifact(1, "app", "pr", "2026-11-26T00:00:00Z", sha = "aaa"), artifact(2, "app", "pr", "2026-11-25T00:00:00Z", sha = "bbb"))
        assertEquals(listOf(2L), buildCandidates(artifacts, "main", emptySet(), headSha = "bbb").map { it.id })
    }

    @Test fun picksTheAppApkNotTestApks() {
        assertEquals("app-debug.apk", pickApk(listOf("output-metadata.json", "app-debug-androidTest.apk", "app-debug.apk")))
        assertEquals("splits/app-universal.apk", pickApk(listOf("splits/app-arm64.apk", "splits/app-universal.apk")))
        assertNull(pickApk(listOf("report.html")))
    }

    @Test fun extractsTheApkFromAnArtifactZip() {
        val dir = Files.createTempDirectory("builds").toFile()
        val zip = File(dir, "a.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../evil/metadata.json")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("app-debug.apk")); z.write("APK".toByteArray()); z.closeEntry()
        }
        val apk = File(dir, "app.apk")
        assertEquals("app-debug.apk", extractApk(zip, apk))
        assertEquals("APK", apk.readText())
        assertFalse(File(dir.parentFile, "evil").exists())
    }

    @Test fun zipsWithoutAnApkAreReported() {
        val dir = Files.createTempDirectory("builds").toFile()
        val zip = File(dir, "a.zip")
        ZipOutputStream(zip.outputStream()).use { z -> z.putNextEntry(ZipEntry("results.xml")); z.write(1); z.closeEntry() }
        assertNull(extractApk(zip, File(dir, "app.apk")))
    }

    @Test fun findsApksAttachedToReleases() {
        val apks = releaseApks(JSONObject("""{"tag_name":"v1.0.0","assets":[{"id":1,"name":"varde-v1.0.0.apk","size":3000000},{"id":2,"name":"notes.txt","size":10}]}"""))
        assertEquals(listOf(ReleaseApk(1, "varde-v1.0.0.apk", 3000000, "v1.0.0", "")), apks)
    }

    @Test fun buildRoutesRoundTrip() {
        assertEquals(WorkspacePage.Builds(), WorkspacePage.parse("builds"))
        assertEquals(WorkspacePage.Builds("abc1234"), WorkspacePage.parse("builds:abc1234"))
        assertNull(WorkspacePage.parse("builds:../x"))
        assertTrue(WorkspacePage.Builds().source(Repo(1, "r", "o", "", false, false, "")).path.endsWith("/actions/artifacts?per_page=100"))
    }

    @Test fun sizesReadNaturally() {
        assertEquals("14.0 MB", megabytes(14_000_000))
        assertEquals("512 KB", megabytes(512_000))
    }
}
