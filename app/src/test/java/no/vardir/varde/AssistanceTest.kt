package no.vardir.varde

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AssistanceTest {
    private class FakeModel(val limit: Int = 4000, val reply: (String) -> String) : TextModel {
        val prompts = mutableListOf<String>()
        override suspend fun status() = ModelStatus.Available
        override fun download() = emptyFlow<DownloadState>()
        override suspend fun tokenLimit() = limit
        override suspend fun generate(prompt: String, maxOutputTokens: Int): String { prompts += prompt; return reply(prompt) }
    }

    @Test fun parsesTaskAndCriteria() {
        val draft = parseBriefDraft("""
            TASK: Make the deploy job retry flaky uploads.
            CRITERIA:
            - Upload step retries up to 3 times
            2. Job fails with a clear message after the last retry
            * Existing tests still pass
        """.trimIndent())!!
        assertEquals("Make the deploy job retry flaky uploads.", draft.task)
        assertEquals(3, draft.criteria.size)
        assertEquals("Job fails with a clear message after the last retry", draft.criteria[1])
        assertTrue(draft.asRequest().contains("Acceptance criteria:\n- Upload step retries up to 3 times"))
    }

    @Test fun draftNeverCarriesTrackingIds() {
        val draft = parseBriefDraft("TASK: Fix login varde12345678\nCRITERIA:\n- Tracking ID: varde12345678\n- Login works")!!
        assertFalse(draft.asRequest().contains("varde12345678"))
        assertEquals(listOf("Login works"), draft.criteria)
    }

    @Test fun unstructuredOrEmptyOutput() {
        assertEquals("Just do the thing.", parseBriefDraft("Just do the thing.")!!.task)
        assertNull(parseBriefDraft("   "))
        assertNull(parseBriefDraft("```\n```"))
    }

    @Test fun promptsStayWithinTheModelLimit() = runBlocking {
        val model = FakeModel(limit = 1000) { "TASK: ok" }
        draftBrief(model, "Fix it", "x".repeat(50_000))
        val budgetChars = (1000 - 400) * 3
        assertTrue(model.prompts.single().length <= budgetChars + 200)
    }

    @Test fun rewriteCleansPreamblesAndRejectsRunaways() {
        assertEquals("Please add a test for the offline case.", cleanRewrite("Here is the rewritten comment:\n\"Please add a test for the offline case.\"", "add test offline"))
        assertNull(cleanRewrite("", "x"))
        assertNull(cleanRewrite("y".repeat(1000), "short"))
        runBlocking {
            assertEquals("Clearer.", rewriteComment(FakeModel { "Clearer." }, "unclear"))
        }
    }

    @Test fun logSuggestionsOnlyUseRealLinesTheModelSaw() = runBlocking {
        val log = (1..100).joinToString("\n") { if (it == 60) "ERROR: upload failed: 403" else "step $it ok" }
        val model = FakeModel { "60, 61, 999, 3" } // 999 doesn't exist; 3 wasn't shown (no keyword, not in the tail)
        val excerpt = suggestLogExcerpt(model, log)!!
        assertTrue(excerpt.contains("ERROR: upload failed: 403"))
        assertFalse(excerpt.contains("step 3 ok\n"))
        excerpt.lines().filter { it != "…" }.forEach { assertTrue(it in log.lines()) }
    }

    @Test fun lineNumberParsingIsBounded() {
        assertEquals(listOf(5, 7), parseLineNumbers("Lines 5 and 7, also 5 and 42", setOf(5, 7, 9)))
        assertTrue(parseLineNumbers("no numbers", setOf(1)).isEmpty())
        assertEquals("b\nc\nd", excerptAround("a\nb\nc\nd\ne", listOf(3), context = 1))
        assertEquals("a\n…\ne", excerptAround("a\nb\nc\nd\ne", listOf(1, 5), context = 0))
    }

    @Test fun budgetNeverNegative() {
        assertEquals(0, inputBudget(tokenLimit = 100, outputTokens = 400, fixedPromptChars = 500))
    }

    @Test fun failuresExplainThemselves() {
        assertTrue(assistFailure(AssistException(-101, "incompatible")).contains("Google AICore"))
        assertTrue(assistFailure(AssistException(-101, "incompatible")).contains("Details: incompatible"))
        assertTrue(assistFailure(AssistException(30, "bg")).contains("open on screen"))
        assertFalse(assistFailure(AssistException(30, "bg")).contains("Details"))
        assertEquals("On-device AI failed (error 4).\nDetails: boom", assistFailure(AssistException(4, "boom")))
        assertTrue(assistFailure(null).contains("nothing usable"))
        assertEquals("On-device AI failed.\nDetails: x", assistFailure(IllegalStateException("x")))
    }

    // ---- Release summaries ----

    private val githubNotes = """## What's Changed
* Install builds from workflow runs by @dev in https://github.com/o/r/pull/18
* Name debug builds after the latest release by @dev in https://github.com/o/r/pull/19

## New Contributors
* @newbie made their first contribution in https://github.com/o/r/pull/17

**Full Changelog**: https://github.com/o/r/compare/v0.1.0...v0.2.0"""

    @Test fun changeTitlesComeFromGitHubNotesWithoutAuthorsOrLinks() {
        assertEquals(listOf("Install builds from workflow runs", "Name debug builds after the latest release"), changeTitles(githubNotes))
    }

    @Test fun mergeCommitsAreNotChanges() {
        assertEquals(listOf("Fix logs"), commitTitles(listOf("Merge pull request #3 from o/branch", "Fix logs", " ", "Merge branch 'main'")))
    }

    @Test fun releasePromptsHoldOnlyChangeTitlesWithinBudget() {
        val prompt = releasePrompt("v0.2.0", List(100) { "Change number $it" }, budget = 200)
        assertTrue(prompt.contains("version v0.2.0"))
        assertTrue(prompt.contains("- Change number 0"))
        assertFalse(prompt.contains("Change number 99"))
    }

    @Test fun releaseSummariesLoseLinksAndPreambles() {
        val cleaned = cleanReleaseSummary("Here are the release notes:\nYou can now install builds straight from workflow runs.\n- See https://example.com\n- Debug builds show the release they follow.")
        assertEquals("You can now install builds straight from workflow runs.\n- Debug builds show the release they follow.", cleaned)
        assertNull(cleanReleaseSummary("  "))
    }

    @Test fun releaseSummaryUsesTheChangesItIsGiven() = runBlocking {
        var seen = ""
        val model = FakeModel { prompt -> seen = prompt; "Installing builds is now one tap from a workflow run." }
        assertEquals("Installing builds is now one tap from a workflow run.", draftReleaseSummary(model, "v0.2.0", changeTitles(githubNotes)))
        assertTrue(seen.contains("- Install builds from workflow runs\n"))
        assertFalse(seen.contains("https://"))
        assertFalse(seen.contains("@dev"))
        assertNull(draftReleaseSummary(model, "v0.2.0", emptyList()))
    }
}
