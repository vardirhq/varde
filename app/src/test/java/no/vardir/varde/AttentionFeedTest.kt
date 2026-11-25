package no.vardir.varde

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AttentionFeedTest {
    private val repo = Repo(42, "demo", "owner", "", false, false, "https://github.com/owner/demo")
    @Test fun collectsOnlyIncludedReviewsAndLatestFailedRuns() = runBlocking {
        val feed = loadAttentionFeed(listOf(repo), "chris") { path ->
            when {
                path.contains("review-requested") -> """{"items":[{"title":"Review me","repository_url":"https://api.github.com/repos/owner/demo","html_url":"https://github.com/owner/demo/pull/1"},{"title":"Excluded","repository_url":"https://api.github.com/repos/other/demo","html_url":"https://github.com/other/demo/pull/2"}]}"""
                path.startsWith("search/") -> """{"items":[]}"""
                else -> """{"workflow_runs":[{"workflow_id":1,"name":"CI","conclusion":"success"},{"workflow_id":1,"name":"CI","conclusion":"failure"},{"workflow_id":2,"name":"Android","conclusion":"failure","html_url":"https://github.com/owner/demo/actions/runs/2"}]}"""
            }
        }
        assertEquals(listOf(AttentionKind.ReviewRequested, AttentionKind.WorkflowFailed), feed.items.map { it.kind })
        assertFalse(feed.incomplete)
    }
    @Test fun reportsPartialResultsWhenAnEndpointFails() = runBlocking {
        val feed = loadAttentionFeed(listOf(repo), "chris") { path ->
            if (path.startsWith("search/")) """{"items":[]}""" else throw java.io.IOException("offline")
        }
        assertTrue(feed.incomplete)
        assertTrue(feed.items.isEmpty())
    }
    @Test fun pathsEncodeSpecialCharacters() {
        assertEquals("a%20b%2Fc", pathPart("a b/c"))
    }
}
