package no.vardir.varde

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DismissalsTest {
    private fun item(kind: AttentionKind, url: String, updated: String = "") = AttentionItem("t", "o/r", kind, url, 1, updated)
    private val run = item(AttentionKind.WorkflowFailed, "https://github.com/o/r/actions/runs/1")
    private val pull = item(AttentionKind.ChecksFailing, "https://github.com/o/r/pull/2")

    @Test fun dismissingCoversCurrentReasonsOnly() {
        val keys = dismissalKeys(pull)
        assertTrue(isDismissed(pull, keys))
        // A new reason on the same PR shows it again.
        assertFalse(isDismissed(pull.copy(alsoKinds = setOf(AttentionKind.ReviewRequested)), keys))
        assertFalse(isDismissed(pull, emptySet()))
    }

    @Test fun newFailingRunIsNotHidden() {
        val keys = dismissalKeys(run)
        assertFalse(isDismissed(run.copy(url = "https://github.com/o/r/actions/runs/2"), keys))
    }

    @Test fun dismissalsExpireWhenTheItemLeaves() {
        val keys = dismissalKeys(run) + dismissalKeys(pull)
        assertEquals(dismissalKeys(pull), liveDismissals(keys, listOf(pull)))
        assertTrue(liveDismissals(keys, emptyList()).isEmpty())
    }

    @Test fun staleFailuresUseAgeAndIgnoreUndated() {
        val now = Instant.parse("2026-11-26T12:00:00Z")
        val old = run.copy(updatedAt = "2026-10-01T00:00:00Z")
        val recent = pull.copy(updatedAt = "2026-11-25T00:00:00Z")
        val undated = item(AttentionKind.WorkflowFailed, "u")
        val review = item(AttentionKind.ReviewRequested, "r", "2026-01-01T00:00:00Z")
        assertEquals(listOf(old), staleFailures(listOf(old, recent, undated, review), now))
    }

    @Test fun runInfoAndRefusalMessages() {
        val info = parseRunInfo("""{"id":5,"run_number":12,"workflow_id":99,"name":"CI","status":"completed","html_url":"https://github.com/o/r/actions/runs/5"}""")!!
        assertEquals(99L, info.workflowId)
        assertNull(parseRunInfo("{}"))
        val refused = actionsWriteFailure(403, "delete this run", """{"message":"Resource not accessible by integration"}""")
        assertTrue(refused.contains("HTTP 403") && refused.contains("Resource not accessible") && refused.contains("write access"))
        assertTrue(actionsWriteFailure(409, "delete this run", "").contains("in progress"))
    }
}
