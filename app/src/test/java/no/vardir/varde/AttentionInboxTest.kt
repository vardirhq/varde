package no.vardir.varde

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AttentionInboxTest {
    private val repo = Repo(1, "demo", "owner", "", false, false, "https://github.com/owner/demo")
    private fun item(id: Long, url: String) = AttentionItem("t", "owner/demo", AttentionKind.AssignedIssue, url, id)

    @Test fun assignedFailureIsReportedNotEmpty() = runBlocking {
        val inbox = AttentionInbox(this)
        inbox.loadAssigned { throw java.io.IOException("offline") }
        assertFalse(inbox.assignedLoaded)
        assertNotNull(inbox.assignedError)
        assertTrue(inbox.mayBeIncomplete(offline = false))
    }

    @Test fun expiredSessionIsRethrownForSignOut() = runBlocking {
        val inbox = AttentionInbox(this)
        assertThrows(Unauthorized::class.java) { runBlocking { inbox.loadAssigned { throw Unauthorized() } } }
        Unit
    }

    @Test fun itemsLeaveOutHiddenRepositories() = runBlocking {
        val inbox = AttentionInbox(this)
        inbox.loadAssigned { listOf(item(1, "a"), item(2, "b")) to false }
        assertEquals(listOf("a"), inbox.items(excluded = setOf(2L)).map { it.url })
        assertTrue(inbox.mayBeIncomplete(offline = true))
    }

    @Test fun newerFeedReplacesOlderOne() = runBlocking {
        val inbox = AttentionInbox(this)
        val release = CompletableDeferred<Unit>()
        val first = inbox.startFeed(listOf(repo), "me") { release.await(); """{"items":[]}""" }!!
        val second = inbox.startFeed(listOf(repo), "me") { path ->
            if (path.startsWith("search/")) """{"items":[]}"""
            else """{"workflow_runs":[{"workflow_id":1,"name":"CI","conclusion":"failure","html_url":"https://github.com/owner/demo/actions/runs/5"}]}"""
        }!!
        second.join()
        assertTrue(first.isCancelled)
        assertFalse(inbox.feedLoading)
        assertEquals(listOf(AttentionKind.WorkflowFailed), inbox.feedItems.map { it.kind })
        release.complete(Unit)
        Unit
    }

    @Test fun clearResetsEverything() = runBlocking {
        val inbox = AttentionInbox(this)
        inbox.loadAssigned { listOf(item(1, "a")) to false }
        inbox.clear()
        assertTrue(inbox.assigned.isEmpty())
        assertFalse(inbox.assignedLoaded)
        assertNull(inbox.startFeed(listOf(repo), "") { "" })
    }
}
