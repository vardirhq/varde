package no.vardir.varde

import org.junit.Assert.*
import org.junit.Test

class AlertsTest {
    private fun item(kind: AttentionKind, url: String) = AttentionItem("Title", "o/r", kind, url, 9)
    private val failing = item(AttentionKind.ChecksFailing, "https://github.com/o/r/pull/4")
    private val review = item(AttentionKind.ReviewRequested, "https://github.com/o/r/pull/5")
    private val assigned = item(AttentionKind.AssignedIssue, "https://github.com/o/r/issues/6")
    private val all = AlertCategory.entries.toSet()

    @Test fun onlyUrgentInboxItemsAlert() {
        val alerts = attentionAlerts(listOf(failing, review, assigned))
        assertEquals(listOf(AlertCategory.Failures, AlertCategory.Reviews), alerts.map { it.category })
        assertEquals("pull:4", alerts.first().page)
    }

    @Test fun duplicatePullAlertsOnceWithMostUrgentKind() {
        val alerts = attentionAlerts(listOf(review.copy(url = failing.url), failing))
        assertEquals(1, alerts.size)
        assertEquals(AlertCategory.Failures, alerts.single().category)
    }

    @Test fun firstRunOnlyRecords() {
        val current = attentionAlerts(listOf(failing, review))
        val decision = decideAlerts(current, emptySet(), all, firstRun = true, complete = true)
        assertTrue(decision.post.isEmpty())
        assertEquals(current.map { it.key }.toSet(), decision.seen)
    }

    @Test fun newItemsPostOnce() {
        val first = decideAlerts(attentionAlerts(listOf(failing)), emptySet(), all, firstRun = true, complete = true)
        val second = decideAlerts(attentionAlerts(listOf(failing, review)), first.seen, all, firstRun = false, complete = true)
        assertEquals(listOf(AlertCategory.Reviews), second.post.map { it.category })
        val third = decideAlerts(attentionAlerts(listOf(failing, review)), second.seen, all, firstRun = false, complete = true)
        assertTrue(third.post.isEmpty())
    }

    @Test fun disabledCategoriesAreRecordedButNotPosted() {
        val decision = decideAlerts(attentionAlerts(listOf(review)), emptySet(), setOf(AlertCategory.Failures), firstRun = false, complete = true)
        assertTrue(decision.post.isEmpty())
        assertEquals(1, decision.seen.size)
    }

    @Test fun partialChecksDontForgetMissingItems() {
        val seen = setOf("old-key")
        val partial = decideAlerts(attentionAlerts(listOf(failing)), seen, all, firstRun = false, complete = false)
        assertTrue("old-key" in partial.seen)
        val full = decideAlerts(attentionAlerts(listOf(failing)), seen, all, firstRun = false, complete = true)
        assertFalse("old-key" in full.seen)
    }

    @Test fun delegationsAlertPerStageWhenTheNextStepIsYours() {
        val d = Delegation("varde12345678", 9, "o/r", "Fix login", "", pullNumber = 7)
        fun alert(stage: DelegationStage) = delegationAlerts(listOf(d to DelegationStatus(stage, "detail")))
        assertTrue(alert(DelegationStage.Waiting).isEmpty())
        assertTrue(alert(DelegationStage.InProgress).isEmpty())
        val failing = alert(DelegationStage.ChecksFailing).single()
        val ready = alert(DelegationStage.ReadyForReview).single()
        assertNotEquals(failing.key, ready.key)
        assertEquals("pull:7", ready.page)
    }

    @Test fun notificationPagesAreRestricted() {
        assertTrue(isNotificationPage("pull:12"))
        assertTrue(isNotificationPage("run:123456789"))
        assertFalse(isNotificationPage("files:../secret"))
        assertFalse(isNotificationPage("pull:12; drop"))
        assertFalse(isNotificationPage("log:1:2"))
    }
}
