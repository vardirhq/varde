package no.vardir.varde

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTest {
    @Test fun completedRunsUseTheirConclusion() {
        assertEquals(StatusLabel(StatusTone.Success, "Passed"), ciStatus("completed", "success"))
        assertEquals(StatusLabel(StatusTone.Failure, "Failed"), ciStatus("completed", "failure"))
        assertEquals(StatusLabel(StatusTone.Failure, "Timed out"), ciStatus("completed", "timed_out"))
        assertEquals(StatusLabel(StatusTone.Failure, "Needs action"), ciStatus("completed", "action_required"))
        assertEquals(StatusLabel(StatusTone.Neutral, "Cancelled"), ciStatus("completed", "cancelled"))
        assertEquals(StatusLabel(StatusTone.Neutral, "Skipped"), ciStatus("completed", "skipped"))
    }

    @Test fun unfinishedRunsArePending() {
        assertEquals(StatusLabel(StatusTone.Pending, "Queued"), ciStatus("queued", ""))
        assertEquals(StatusLabel(StatusTone.Pending, "In progress"), ciStatus("in_progress", ""))
        assertEquals(StatusLabel(StatusTone.Pending, "Waiting"), ciStatus("waiting", "null"))
    }

    @Test fun missingOrUnknownValuesStayNeutral() {
        assertEquals(StatusLabel(StatusTone.Neutral, "Unknown"), ciStatus("", ""))
        assertEquals(StatusLabel(StatusTone.Neutral, "Unknown"), ciStatus("completed", "null"))
        assertEquals(StatusLabel(StatusTone.Neutral, "Something new"), ciStatus("completed", "something_new"))
    }

    @Test fun commitStatusesMapLegacyStates() {
        assertEquals(StatusTone.Success, commitStatus("success").tone)
        assertEquals(StatusTone.Failure, commitStatus("error").tone)
        assertEquals(StatusTone.Pending, commitStatus("pending").tone)
        assertEquals(StatusTone.Neutral, commitStatus("").tone)
    }

    @Test fun pullStatePrefersMergedThenDraft() {
        assertEquals("Merged", pullState("closed", draft = false, merged = true).label)
        assertEquals("Draft", pullState("open", draft = true, merged = false).label)
        assertEquals(StatusLabel(StatusTone.Success, "Open"), pullState("open", draft = false, merged = false))
        assertEquals(StatusLabel(StatusTone.Failure, "Closed"), pullState("closed", draft = false, merged = false))
    }

    @Test fun attentionItemsRouteToWorkspacePages() {
        fun item(kind: String, url: String) = AttentionItem("t", "o/r", kind, url, 1)
        assertEquals("run:42", attentionRoute(item("Latest workflow run failed", "https://github.com/o/r/actions/runs/42")))
        assertEquals("issue:7", attentionRoute(item("Issue", "https://github.com/o/r/issues/7")))
        assertEquals("pull:9", attentionRoute(item("Review requested", "https://github.com/o/r/pull/9")))
        assertEquals("pull:9", attentionRoute(item("PR checks failing", "https://github.com/o/r/pull/9")))
        assertEquals("pull:9", attentionRoute(item("Pull request", "https://github.com/o/r/pull/9")))
        assertEquals(null, attentionRoute(item("Issue", "https://github.com/o/r/issues/abc")))
        assertEquals(null, attentionRoute(item("Something else", "https://github.com/o/r/pull/9")))
    }
}
