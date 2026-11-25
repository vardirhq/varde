package no.vardir.varde

import org.junit.Assert.*
import org.junit.Test

class HandoverTest {
    @Test fun excerptIncludesContextAndIsBounded() {
        val lines = (1..100).map { if (it == 50) "ERROR: compilation failed" else "line $it" }.joinToString("\n")
        val result = failureExcerpt(lines, context = 1, maxLines = 3)
        assertEquals("line 49\nERROR: compilation failed\nline 51", result)
        assertEquals("", failureExcerpt("all good\nstill good"))
    }
    @Test fun briefIncludesRequestEvidenceAndInstructions() {
        val brief = buildHandover("https://github.com/a/b", "pull:7", "Fix tests", "CI failed",
            listOf("AGENTS.md (repository default branch)" to "Run tests"))
        assertTrue(brief.contains("Fix tests"))
        assertTrue(brief.contains("CI failed"))
        assertTrue(brief.contains("Run tests"))
        assertTrue(buildHandover("repo", "overview", "", "", emptyList()).contains("acceptance criteria"))
    }
    @Test fun recognizesOnlyGithubActionJobLinks() {
        assertEquals("log:456:123", actionJobRoute("https://github.com/a/b/actions/runs/123/job/456"))
        assertNull(actionJobRoute("https://attacker.example/a/b/actions/runs/123/job/456"))
        assertNull(actionJobRoute("https://github.com/a/b/actions/runs/123"))
    }
    @Test fun detectsFailedJobsWithoutMistakingPendingForFailure() {
        assertTrue(hasFailedJobs("""{"jobs":[{"conclusion":"failure"}]}"""))
        assertFalse(hasFailedJobs("""{"jobs":[{"conclusion":null}]}"""))
        assertFalse(hasFailedJobs("invalid"))
    }
}
