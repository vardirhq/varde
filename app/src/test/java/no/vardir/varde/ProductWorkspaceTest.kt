package no.vardir.varde

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProductWorkspaceTest {
    @Test fun previousPortfolioKeepsLegacyNotes() {
        val old = """{"version":1,"products":[{"id":"p","name":"Sindri","milestones":"Ship demo","ideas":"New game","repositories":[1]}]}"""
        val product = decodeProducts(old).single()
        assertEquals("Ship demo", product.milestones)
        assertEquals("New game", product.ideas)
        assertTrue(product.work.isEmpty())
        assertEquals(product, decodeProducts(encodeProducts(listOf(product))).single())
    }
    @Test fun backupRoundTripsWorkAndRejectsOtherAccounts() {
        val product = Product("p", "Varde", work = listOf(WorkItem("w", "Try this", "Idea", "Details",
            "https://github.com/vardirhq/varde/issues/1")))
        val backup = encodePortfolioBackup("Chris", listOf(product))
        assertEquals(listOf(product), decodePortfolioBackup(backup, "chris"))
        assertThrows(IllegalArgumentException::class.java) { decodePortfolioBackup(backup, "someone-else") }
    }
    @Test fun workLinksRequireGithubIssueOrPr() {
        assertTrue(validWorkLink(""))
        assertTrue(validWorkLink("https://github.com/vardirhq/varde/pull/9"))
        assertFalse(validWorkLink("https://github.com.evil.test/a/b/issues/1"))
        assertFalse(validWorkLink("javascript:alert(1)"))
    }
    @Test fun combinedEventsAreSortedAndIdentifyTheirRepositories() = runBlocking {
        val repo = Repo(1, "demo", "owner", "", false, false, "https://github.com/owner/demo")
        val result = loadProductEvidence(listOf(repo)) { path ->
            val body = when {
                path.contains("/pulls?") -> """[{"number":7,"title":"PR","updated_at":"2026-01-02T00:00:00Z","html_url":"https://github.com/owner/demo/pull/7"}]"""
                path.contains("/issues?") -> """[{"number":3,"title":"Issue","updated_at":"2026-01-03T00:00:00Z"}]"""
                else -> """[{"sha":"abc","commit":{"message":"Commit","committer":{"date":"2026-01-01T00:00:00Z"}}}]"""
            }
            CachedResponse(body, false, 0)
        }
        assertEquals(listOf("Issue", "PR", "Commit"), result.events.map { it.title })
        assertEquals(listOf("issue:3", "pull:7", "commits"), result.events.map { it.route })
        assertTrue(result.events.all { it.repositoryId == 1L })
    }
}
