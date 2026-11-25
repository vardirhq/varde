package no.vardir.varde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AttentionTest {
    private fun item(kind: AttentionKind, url: String, updated: String = "", title: String = "t") =
        AttentionItem(title, "o/r", kind, url, 1, updated)

    @Test fun itemsRouteToWorkspacePages() {
        assertEquals("run:42", attentionRoute(item(AttentionKind.WorkflowFailed, "https://github.com/o/r/actions/runs/42")))
        assertEquals("issue:7", attentionRoute(item(AttentionKind.AssignedIssue, "https://github.com/o/r/issues/7")))
        assertEquals("pull:9", attentionRoute(item(AttentionKind.ReviewRequested, "https://github.com/o/r/pull/9")))
        assertEquals("pull:9", attentionRoute(item(AttentionKind.ChecksFailing, "https://github.com/o/r/pull/9")))
        assertEquals("pull:9", attentionRoute(item(AttentionKind.AssignedPullRequest, "https://github.com/o/r/pull/9")))
        assertNull(attentionRoute(item(AttentionKind.AssignedIssue, "https://github.com/o/r/issues/abc")))
    }

    @Test fun duplicatesMergeIntoMostUrgentKindWithNewestTime() {
        val url = "https://github.com/o/r/pull/9"
        val merged = mergeAttention(listOf(
            item(AttentionKind.AssignedPullRequest, url, "2026-11-20T10:00:00Z"),
            item(AttentionKind.ReviewRequested, url, "2026-11-24T10:00:00Z"),
            item(AttentionKind.ChecksFailing, url, "2026-11-22T10:00:00Z"),
        )).single()
        assertEquals(AttentionKind.ChecksFailing, merged.kind)
        assertEquals(setOf(AttentionKind.ReviewRequested, AttentionKind.AssignedPullRequest), merged.alsoKinds)
        assertEquals("2026-11-24T10:00:00Z", merged.updatedAt)
    }

    @Test fun sectionsAreOrderedAndNewestFirst() {
        val sections = inboxSections(listOf(
            item(AttentionKind.AssignedIssue, "u1", "2026-11-24T00:00:00Z", "issue"),
            item(AttentionKind.ReviewRequested, "u2", "2026-11-20T00:00:00Z", "older review"),
            item(AttentionKind.ReviewRequested, "u3", "2026-11-23T00:00:00Z", "newer review"),
            item(AttentionKind.ReviewRequested, "u4", "", "undated review"),
            item(AttentionKind.WorkflowFailed, "u5", "2026-11-01T00:00:00Z", "ci"),
        ))
        assertEquals(listOf(InboxSection.Failing, InboxSection.Review, InboxSection.Assigned), sections.map { it.first })
        assertEquals(listOf("newer review", "older review", "undated review"), sections[1].second.map { it.title })
    }

    @Test fun emptySectionsAreOmitted() {
        assertEquals(listOf(InboxSection.Assigned),
            inboxSections(listOf(item(AttentionKind.AssignedIssue, "u1"))).map { it.first })
    }

    @Test fun relativeAgeIsCompact() {
        val now = Instant.parse("2026-11-25T12:00:00Z")
        assertEquals("now", relativeAge("2026-11-25T11:59:30Z", now))
        assertEquals("12m", relativeAge("2026-11-25T11:48:00Z", now))
        assertEquals("5h", relativeAge("2026-11-25T07:00:00Z", now))
        assertEquals("3d", relativeAge("2026-11-22T12:00:00Z", now))
        assertEquals("3w", relativeAge("2026-11-04T12:00:00Z", now))
        assertEquals("4mo", relativeAge("2026-07-25T12:00:00Z", now))
        assertEquals("now", relativeAge("2026-11-26T12:00:00Z", now))
        assertNull(relativeAge("", now))
        assertNull(relativeAge("not a date", now))
    }
}
