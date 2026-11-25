package no.vardir.varde

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PortfolioTest {
    private val repo = Repo(1, "engine", "vardir", "", false, false, "https://github.com/vardir/engine")
    @Test fun productRoundTripPreservesNotesAndRepositoryOrder() {
        val product = Product("id", "Sindri", "Engine", "Building", "High", "Fix physics",
            "Release demo\nShip docs", "New game", listOf(2, 1), true)
        assertEquals(listOf(product), decodeProducts(encodeProducts(listOf(product))))
    }
    @Test fun unknownVersionFailsInsteadOfOverwritingData() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeProducts("""{"version":2,"products":[]}""")
        }
    }
    @Test fun moveHonorsBoundariesAndPreservesUnavailableRepositories() {
        assertEquals(listOf(2L, 1L, 99L), moveRepository(listOf(1, 2, 99), 1, -1))
        assertEquals(listOf(1L, 2L), moveRepository(listOf(1, 2), 0, -1))
    }
    @Test fun attentionExcludesHiddenRepositories() {
        val items = listOf(AttentionItem("Failure", "vardir/engine", "Latest workflow run failed", "url", 1))
        assertEquals(1, productAttention(Product("p", "X", repositoryIds = listOf(1)), items, emptySet()).size)
        assertTrue(productAttention(Product("p", "X", repositoryIds = listOf(1)), items, setOf(1)).isEmpty())
    }
    @Test fun evidenceReportsCachedAndMissingData() = runBlocking {
        val result = loadProductEvidence(listOf(repo)) { path ->
            if (path.contains("/pulls?")) CachedResponse(
                """[{"title":"Fix","html_url":"https://github.com/vardir/engine/pull/4"}]""", true, 0)
            else throw java.io.IOException("offline")
        }
        assertEquals("Fix", result.pullRequests.single().title)
        assertTrue(result.notes.any { it.contains("cached") })
        assertTrue(result.notes.any { it.contains("could not be loaded") })
        assertTrue(result.activity.isEmpty())
    }
}
