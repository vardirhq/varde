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
    @Test fun onlyTheDefaultBranchCounts() {
        val main = repo.copy(defaultBranch = "main")
        val runs = """{"workflow_runs":[
            {"workflow_id":1,"name":"CI","conclusion":"success","head_branch":"feature","event":"push"},
            {"workflow_id":1,"name":"CI","conclusion":"failure","head_branch":"main","event":"push","html_url":"https://github.com/owner/demo/actions/runs/9"},
            {"workflow_id":2,"name":"PR","conclusion":"failure","head_branch":"main","event":"pull_request"},
            {"workflow_id":3,"name":"Old","conclusion":"failure","head_branch":"stale","event":"push"}]}"""
        assertEquals(listOf("https://github.com/owner/demo/actions/runs/9"), failedDefaultBranchRuns(main, runs).map { it.url })
        assertTrue(feedRunsPath(main).endsWith("&branch=main"))
    }

    @Test fun rateLimitsStopTheFeedAndExplainWhy() = runBlocking {
        var calls = 0
        val feed = loadAttentionFeed(listOf(repo), "chris") { calls++; throw RateLimited(null) }
        assertTrue(feed.incomplete)
        assertNotNull(feed.problem)
        assertEquals(1, calls)
    }

    @Test fun findsReviewsOfYourPullRequestsAndMentions() = runBlocking {
        val feed = loadAttentionFeed(listOf(repo), "chris") { path ->
            val q = java.net.URLDecoder.decode(path, "UTF-8")
            when {
                q.contains("review:changes_requested") -> """{"items":[{"title":"Fix","repository_url":"https://api.github.com/repos/owner/demo","html_url":"https://github.com/owner/demo/pull/3"}]}"""
                q.contains("mentions:chris") -> """{"items":[{"title":"Question","repository_url":"https://api.github.com/repos/owner/demo","html_url":"https://github.com/owner/demo/issues/4"}]}"""
                path.startsWith("search/") -> """{"items":[]}"""
                else -> """{"workflow_runs":[]}"""
            }
        }
        assertEquals(listOf(AttentionKind.ChangesRequested, AttentionKind.Mentioned), feed.items.map { it.kind })
        assertEquals("issue:4", attentionRoute(feed.items[1]))
    }

    @Test fun pathsEncodeSpecialCharacters() {
        assertEquals("a%20b%2Fc", pathPart("a b/c"))
    }
}
