package no.vardir.varde

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DelegationTest {
    private val repo = Repo(7, "app", "owner", "", false, false, "https://github.com/owner/app")
    private val sha = "b".repeat(40)
    private fun d(pull: Int? = null, startSha: String = "") =
        Delegation("varde12345678", 7, "owner/app", "Fix login", "2026-11-26T10:00:00Z", pull, startSha)
    private fun pr(state: String = "open", draft: Boolean = false, merged: Boolean = false, head: String = sha) = parsePullRequest("""
        {"number":5,"title":"t","state":"$state","draft":$draft,"merged":$merged,"head":{"ref":"x","sha":"$head"},"base":{"ref":"main"},
         "html_url":"https://github.com/owner/app/pull/5"}""")
    private fun checks(passed: Int = 0, failed: Int = 0, pending: Int = 0) =
        CheckSummary(passed + failed + pending, passed, failed, pending, truncated = false, cached = false)

    @Test fun trackingIdsAreSearchableSingleWords() {
        repeat(20) { assertTrue(isTrackingId(newTrackingId())) }
        assertFalse(isTrackingId("varde-1234567"))
        assertTrue(trackingInstruction("varde12345678").contains("Tracking ID: varde12345678"))
    }

    @Test fun stagesFollowThePullRequest() {
        assertEquals(DelegationStage.Waiting, delegationStatus(d(), null, null).stage)
        assertEquals(DelegationStage.ChecksFailing, delegationStatus(d(5), pr(), checks(passed = 2, failed = 1)).stage)
        assertEquals(DelegationStage.InProgress, delegationStatus(d(5), pr(), checks(pending = 1)).stage)
        assertEquals(DelegationStage.InProgress, delegationStatus(d(5), pr(draft = true), checks(passed = 1)).stage)
        assertEquals(DelegationStage.InProgress, delegationStatus(d(5), pr(), null).stage)
        assertEquals(DelegationStage.ReadyForReview, delegationStatus(d(5), pr(), checks(passed = 3)).stage)
        assertEquals(DelegationStage.Merged, delegationStatus(d(5), pr(state = "closed", merged = true), null).stage)
        assertEquals(DelegationStage.Closed, delegationStatus(d(5), pr(state = "closed"), null).stage)
    }

    @Test fun existingPullWaitsForNewCommits() {
        assertEquals(DelegationStage.Waiting, delegationStatus(d(5, startSha = sha), pr(), checks(failed = 1)).stage)
        assertEquals(DelegationStage.ReadyForReview, delegationStatus(d(5, startSha = sha), pr(head = "c".repeat(40)), checks(passed = 1)).stage)
    }

    @Test fun searchFindsThePullThenReadsItsChecks() = runBlocking {
        val paths = mutableListOf<String>()
        val (updated, status) = checkDelegation(d(), repo) { path ->
            paths += path
            CachedResponse(when {
                path.startsWith("search/") -> """{"items":[{"number":3},{"number":5}]}"""
                path.endsWith("/pulls/5") -> """{"number":5,"state":"open","head":{"ref":"x","sha":"$sha"},"base":{"ref":"main"}}"""
                path.contains("/check-runs") -> """{"total_count":1,"check_runs":[{"status":"completed","conclusion":"success"}]}"""
                else -> error("unexpected $path")
            }, false, 0)
        }
        assertEquals(5, updated.pullNumber)
        assertEquals(DelegationStage.ReadyForReview, status.stage)
        assertTrue(paths.first().contains("varde12345678"))
        assertTrue(paths.first().contains("repo%3Aowner%2Fapp"))
    }

    @Test fun noMatchKeepsWaitingAndFailuresAreUnknown() = runBlocking {
        val (same, waiting) = checkDelegation(d(), repo) { CachedResponse("""{"items":[]}""", false, 0) }
        assertNull(same.pullNumber)
        assertEquals(DelegationStage.Waiting, waiting.stage)
        val (_, unknown) = checkDelegation(d(5), repo) { throw java.io.IOException("offline") }
        assertEquals(DelegationStage.Unknown, unknown.stage)
        assertEquals(DelegationStage.Unknown, checkDelegation(d(), null) { error("not called") }.second.stage)
    }

    @Test fun expiredSessionIsRethrown() {
        assertThrows(Unauthorized::class.java) { runBlocking { checkDelegation(d(5), repo) { throw Unauthorized() } } }
    }

    @Test fun storageRoundTripsAndRejectsBadData() {
        val items = listOf(d(), d(5, sha).copy(id = "vardeabcdefgh", done = true))
        assertEquals(items, decodeDelegations(encodeDelegations(items)))
        assertThrows(IllegalArgumentException::class.java) {
            decodeDelegations(encodeDelegations(listOf(d().copy(id = "not-an-id"))))
        }
    }
}
