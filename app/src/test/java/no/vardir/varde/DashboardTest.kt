package no.vardir.varde

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTest {
    private fun repo(id: Long, name: String, owner: String, archived: Boolean = false) =
        Repo(id, name, owner, "", archived, false, "https://github.com/$owner/$name")

    @Test fun dashboardShowsAtMostFourNonArchivedRepositoriesInOriginalOrder() {
        val repos = listOf(repo(1, "old", "a", true)) + (2L..7L).map { repo(it, "repo$it", "a") }
        assertEquals(listOf(2L, 3L, 4L, 5L), dashboardRepositories(repos).map { it.id })
    }

    @Test fun searchRespectsOwnerAndArchivedFilters() {
        val repos = listOf(repo(1, "Engine", "Vardir"), repo(2, "Engine Old", "Vardir", true), repo(3, "Engine", "Elsewhere"))
        assertEquals(listOf(1L), filterRepositories(repos, "eng", "Vardir", false).map { it.id })
        assertEquals(listOf(1L, 2L), filterRepositories(repos, "VARDIR", "Vardir", true).map { it.id })
    }
}
