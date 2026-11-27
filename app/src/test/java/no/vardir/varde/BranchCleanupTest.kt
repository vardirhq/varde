package no.vardir.varde

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BranchCleanupTest {
    private fun pr(branch: String, headRepo: String? = "acme/app") = JSONObject("""{"head":{"ref":"$branch","repo":${headRepo?.let { """{"full_name":"$it"}""" } ?: "null"}},
        "base":{"ref":"main","repo":{"full_name":"acme/app"}}}""")
    private val repo = JSONObject("""{"default_branch":"main","delete_branch_on_merge":false}""")

    @Test fun offeredForAnOrdinaryBranch() {
        assertEquals(BranchCleanup("feature/x", offer = true, automatic = false, reason = null), branchCleanup(pr("feature/x"), repo, 0))
    }

    @Test fun repositoriesThatAutoDeleteNeedNoOption() {
        val c = branchCleanup(pr("feature/x"), JSONObject("""{"default_branch":"main","delete_branch_on_merge":true}"""), 0)
        assertTrue(c.automatic); assertFalse(c.offer)
    }

    @Test fun keptWhenDeletingWouldHurt() {
        assertTrue(branchCleanup(pr("fix", headRepo = "someone/app"), repo, 0).reason!!.contains("fork"))
        assertTrue(branchCleanup(pr("fix", headRepo = null), repo, 0).reason!!.contains("fork"))
        assertTrue(branchCleanup(pr("main"), repo, 0).reason!!.contains("default branch"))
        assertEquals("2 open pull requests target it and would be closed.", branchCleanup(pr("base-for-others"), repo, 2).reason)
        assertFalse(branchCleanup(pr("fix"), repo, null).offer)
    }

    @Test fun branchNamesWithSlashesKeepTheirPath() {
        val r = Repo(1, "app", "acme", "", false, false, "")
        assertEquals("repos/acme/app/git/refs/heads/feature/new%20thing", deleteBranchPath(r, "feature/new thing"))
    }
}
