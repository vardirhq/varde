package no.vardir.varde

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProductWeekTest {
    private val app = Repo(1, "app", "acme", "", false, false, "https://github.com/acme/app", defaultBranch = "main")
    private val api = Repo(2, "api", "acme", "", false, false, "https://github.com/acme/api", defaultBranch = "main")
    private val now = Instant.parse("2026-11-27T12:00:00Z")

    private fun pull(repo: String, number: Int, updated: String, merged: String? = null, draft: Boolean = false) =
        """{"number":$number,"title":"PR $number","repository_url":"https://api.github.com/repos/acme/$repo","updated_at":"$updated","draft":$draft""" +
            (merged?.let { ""","pull_request":{"merged_at":"$it"}""" } ?: "") + "}"

    @Test fun searchResultsStayInTheProduct() {
        val body = """{"total_count":2,"items":[${pull("app", 1, "2026-11-26T00:00:00Z")},${pull("other", 2, "2026-11-26T00:00:00Z")}]}"""
        val (pulls, cut) = parseFoundPulls(body, listOf(app))
        assertEquals(listOf(1), pulls.map { it.number })
        assertFalse(cut)
    }

    @Test fun idleMeansUntouchedForAWeek() {
        val open = parseFoundPulls("""{"items":[${pull("app", 1, "2026-11-10T00:00:00Z", draft = true)},${pull("app", 2, "2026-11-25T00:00:00Z")}]}""", listOf(app)).first
        val idle = idlePulls(open, now)
        assertEquals(listOf("#1 PR 1"), idle.map { it.title })
        assertEquals("draft", idle.single().detail)
        assertEquals("pull:1", idle.single().route)
    }

    @Test fun onlyPublishedReleasesFromThisWeekCount() {
        val body = """[{"tag_name":"v2","published_at":"2026-11-25T00:00:00Z"},{"tag_name":"v3","draft":true,"published_at":"2026-11-26T00:00:00Z"},{"tag_name":"v1","published_at":"2026-10-01T00:00:00Z"}]"""
        assertEquals(listOf("v2"), releasesSince(app, body, now.minusSeconds(7 * 86400)).map { it.title })
    }

    @Test fun aWeekIsGatheredAcrossRepositories() = runBlocking {
        val week = loadProductWeek(listOf(app, api), now) { path ->
            val q = java.net.URLDecoder.decode(path, "UTF-8")
            CachedResponse(when {
                q.contains("is:merged") -> {
                    assertTrue(q.contains("merged:>=2026-11-20"))
                    assertTrue(q.contains("repo:acme/app") && q.contains("repo:acme/api"))
                    """{"items":[${pull("api", 5, "2026-11-26T00:00:00Z", merged = "2026-11-26T09:00:00Z")}]}"""
                }
                q.contains("is:open") -> """{"items":[${pull("app", 3, "2026-11-01T00:00:00Z")}]}"""
                path.startsWith("repos/acme/app/releases") -> """[{"tag_name":"v1.1.0","published_at":"2026-11-27T08:00:00Z"}]"""
                else -> throw java.io.IOException("offline")
            }, false, 0)
        }
        assertEquals(listOf("#5 PR 5"), week.merged.map { it.title })
        assertEquals(listOf("v1.1.0"), week.released.map { it.title })
        assertEquals(listOf("#3 PR 3"), week.idle.map { it.title })
        assertTrue("the api releases failed to load", week.incomplete)
        assertEquals("This week: 1 merged · 1 release · 2 failing · 1 idle PR", weekLine(week, failing = 2))
        assertNull(weekLine(ProductWeek(emptyList(), emptyList(), emptyList(), false, 0), 0))
    }

    @Test fun readinessComesFromReleasesComparesAndChecks() = runBlocking {
        val r = loadReadiness(app) { path ->
            CachedResponse(when {
                path.contains("/releases") -> """[{"tag_name":"v1.1.0","published_at":"2026-11-27T08:00:00Z"}]"""
                path.contains("/compare/v1.1.0...main") -> """{"ahead_by":4,"commits":[]}"""
                path.contains("/commits/main/check-runs") -> """{"total_count":2,"check_runs":[{"status":"completed","conclusion":"success"},{"status":"completed","conclusion":"success"}]}"""
                else -> error(path)
            }, false, 0)
        }
        assertEquals("4 unreleased commits since v1.1.0 · checks passing", readinessLine(r))
        assertEquals(StatusTone.Success, readinessTone(r))
        assertEquals("No releases yet · no checks", readinessLine(RepoReadiness(app, null, null, CheckSummary(0, 0, 0, 0, false, false))))
        assertEquals("Couldn't check this repository", readinessLine(RepoReadiness(app, null, null, null, failed = true)))
    }
}
