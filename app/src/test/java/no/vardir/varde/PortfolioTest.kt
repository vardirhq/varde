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
            decodeProducts("""{"version":3,"products":[]}""")
        }
    }
    @Test fun moveHonorsBoundariesAndPreservesUnavailableRepositories() {
        assertEquals(listOf(2L, 1L, 99L), moveRepository(listOf(1, 2, 99), 1, -1))
        assertEquals(listOf(1L, 2L), moveRepository(listOf(1, 2), 0, -1))
    }
    @Test fun attentionExcludesHiddenRepositories() {
        val items = listOf(AttentionItem("Failure", "vardir/engine", AttentionKind.WorkflowFailed, "url", 1))
        assertEquals(1, productAttention(Product("p", "X", repositoryIds = listOf(1)), items, emptySet()).size)
        assertTrue(productAttention(Product("p", "X", repositoryIds = listOf(1)), items, setOf(1)).isEmpty())
    }
}
