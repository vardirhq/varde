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
    @Test fun issueRequestTargetsTheChosenRepository() {
        val repo = Repo(1, "demo", "owner", "", false, false, "https://github.com/owner/demo")
        val request = buildCreateIssueRequest(repo, "Fix issue", "Details", "test-token")
        assertEquals("POST", request.method)
        assertEquals("/repos/owner/demo/issues", request.url.encodedPath)
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        val json = org.json.JSONObject(buffer.readUtf8())
        assertEquals("Fix issue", json.getString("title"))
        assertEquals("Details", json.getString("body"))
        assertThrows(IllegalArgumentException::class.java) { buildCreateIssueRequest(repo, " ", "", "x") }
    }
}
