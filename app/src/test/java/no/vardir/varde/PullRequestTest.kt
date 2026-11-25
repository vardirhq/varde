package no.vardir.varde

import org.junit.Assert.*
import org.junit.Test

class PullRequestTest {
    private val sha = "a".repeat(40)
    private fun prJson(state: String = "open", draft: Boolean = false, merged: Boolean = false,
                       mergeable: String = "true", mergeableState: String = "clean") = """
        {"number":12,"title":"Add inbox","body":"Body","state":"$state","draft":$draft,"merged":$merged,
         "user":{"login":"octocat"},"head":{"ref":"feature","sha":"$sha"},"base":{"ref":"main"},
         "additions":10,"deletions":2,"changed_files":3,"commits":4,"comments":1,"review_comments":5,
         "mergeable":$mergeable,"mergeable_state":"$mergeableState","updated_at":"2026-11-25T10:00:00Z",
         "html_url":"https://github.com/o/r/pull/12"}
    """.trimIndent()
    private fun pr(state: String = "open", draft: Boolean = false, merged: Boolean = false,
                   mergeable: String = "true", mergeableState: String = "clean") =
        parsePullRequest(prJson(state, draft, merged, mergeable, mergeableState))
    private fun checks(passed: Int = 0, failed: Int = 0, pending: Int = 0) =
        CheckSummary(passed + failed + pending, passed, failed, pending, truncated = false, cached = false)

    @Test fun parsesFieldsAndNullMergeability() {
        val p = pr()
        assertEquals(12, p.number); assertEquals("octocat", p.author); assertEquals("feature", p.headRef)
        assertEquals(sha, p.headSha); assertEquals("main", p.baseRef); assertEquals(5, p.reviewComments)
        assertEquals(true, p.mergeable); assertTrue(p.open)
        assertNull(pr(mergeable = "null").mergeable)
    }

    @Test fun checkRunsAreCountedByStatus() {
        val body = """{"total_count":5,"check_runs":[
            {"status":"completed","conclusion":"success"},{"status":"completed","conclusion":"failure"},
            {"status":"in_progress","conclusion":null},{"status":"completed","conclusion":"skipped"}]}"""
        val summary = parseCheckRuns(body, cached = true)
        assertEquals(CheckSummary(4, 1, 1, 1, truncated = true, cached = true), summary)
    }

    @Test fun failingChecksWinOverEverythingButFinalStates() {
        assertEquals("2 checks failing", pullVerdict(pr(), checks(passed = 3, failed = 2)).status.label)
        assertEquals(StatusTone.Failure, pullVerdict(pr(draft = true), checks(failed = 1)).status.tone)
        assertEquals("Merged", pullVerdict(pr(state = "closed", merged = true), checks(failed = 1)).status.label)
        assertEquals("Closed", pullVerdict(pr(state = "closed"), checks(failed = 1)).status.label)
    }

    @Test fun readinessNeedsPassingChecksAndCleanState() {
        val ready = pullVerdict(pr(), checks(passed = 3))
        assertEquals(StatusLabel(StatusTone.Success, "Ready to merge"), ready.status)
        assertTrue(ready.readyToMerge)
        assertEquals("1 check running", pullVerdict(pr(), checks(passed = 1, pending = 1)).status.label)
        assertEquals("Merge conflicts", pullVerdict(pr(mergeable = "false", mergeableState = "dirty"), checks(passed = 1)).status.label)
        assertEquals("Behind main", pullVerdict(pr(mergeableState = "behind"), checks(passed = 1)).status.label)
        assertEquals("Blocked", pullVerdict(pr(mergeableState = "blocked"), checks(passed = 1)).status.label)
        assertEquals("Draft", pullVerdict(pr(draft = true), checks(passed = 1)).status.label)
    }

    @Test fun unknownChecksAreNeverReportedAsReady() {
        val verdict = pullVerdict(pr(), null)
        assertEquals("Checks unknown", verdict.status.label)
        assertFalse(verdict.readyToMerge)
    }

    @Test fun noChecksIsNeutralAndOnlyReadyWhenGitHubSaysMergeable() {
        assertEquals(StatusTone.Neutral, pullVerdict(pr(), checks()).status.tone)
        assertTrue(pullVerdict(pr(), checks()).readyToMerge)
        assertFalse(pullVerdict(pr(mergeable = "null", mergeableState = "unknown"), checks()).readyToMerge)
    }
}
