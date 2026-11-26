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

    @Test fun releaseChangesKeepTheirPullRequestNumbers() {
        assertEquals(listOf(ReleaseChange("Install builds from workflow runs", 18), ReleaseChange("Name debug builds after the latest release", 19)),
            releaseChanges(githubNotes))
    }

    @Test fun mergeCommitsAreNotChanges() {
        assertEquals(listOf("Fix logs"), commitTitles(listOf("Merge pull request #3 from o/branch", "Fix logs", " ", "Merge branch 'main'")))
    }

    // The description of PR #19, the only change in v0.1.1, shortened.
    private val pr19 = """## Summary

- **Release notes summaries (on-device AI, optional).** When on-device assistance is on, **Draft a release** has a **Write a summary from the changes** button.
  - **The model only sees change titles**, see https://github.com/vardirhq/varde/pull/19.
- **Debug build names.** Debug builds are now named after the latest release tag and their CI run, like `0.2.0-dev.57`.
- **`scripts/create-release-key.sh`** creates the release key on your own computer and sets the four signing secrets.

## Testing
- `gradle :app:testDebugUnitTest` passes with 141 unit tests.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"""

    @Test fun pullSummariesKeepWhatChangedAndDropTestingAndFooters() {
        val summary = pullSummary(pr19)
        assertTrue(summary.contains("Debug builds are now named after the latest release tag"))
        assertTrue(summary.contains("scripts/create-release-key.sh creates the release key"))
        assertFalse(summary.contains("Testing"))
        assertFalse(summary.contains("141 unit tests"))
        assertFalse(summary.contains("http"))
        assertFalse(summary.contains("Generated with"))
        assertTrue(pullSummary("x".repeat(50) + ". " + "y".repeat(2000), maxChars = 100).length <= 100)
    }

    @Test fun nestedDetailDoesNotCrowdOutLaterChanges() {
        val body = "## Summary\n\n- **First feature.** Does one thing.\n" + "  - Detail that goes on and on. ".repeat(40) + "\n- **Second feature.** Does another.\n- **Third.** And a third.\n\n## Testing\n- ok"
        val summary = pullSummary(body)
        assertTrue(summary.contains("Second feature"))
        assertTrue(summary.contains("Third"))
        assertFalse(summary.contains("goes on and on"))
    }

    @Test fun aLoneChangeWithoutDetailsIsNotWorthSummarizing() {
        assertFalse(worthSummarizing(listOf(ReleaseChange("Release notes summaries, debug build names, release key script", 19))))
        assertTrue(worthSummarizing(listOf(ReleaseChange("Release notes summaries, debug build names, release key script", 19, pullSummary(pr19)))))
        assertTrue(worthSummarizing(listOf(ReleaseChange("a"), ReleaseChange("b"))))
        assertFalse(worthSummarizing(emptyList()))
    }

    @Test fun summaryLengthFollowsTheSizeOfTheRelease() {
        assertEquals("Write one or two sentences.", releaseShape(1))
        assertTrue(releaseShape(3).contains("No bullet points"))
        assertTrue(releaseShape(8).contains("bullet points"))
    }

    @Test fun releasePromptsCarryDescriptionsWithinBudget() {
        val prompt = releasePrompt("v0.1.1", listOf(ReleaseChange("Release notes summaries, debug build names, release key script", 19, pullSummary(pr19))), budget = 3000)
        assertTrue(prompt.contains("1. Release notes summaries, debug build names, release key script"))
        assertTrue(prompt.contains("Debug builds are now named after the latest release tag"))
        assertTrue(prompt.contains("Write one or two sentences."))
        assertFalse(prompt.contains("https://"))
        val many = releasePrompt("v1", List(100) { ReleaseChange("Change number $it", details = "d".repeat(500)) }, budget = 400)
        assertTrue(many.contains("1. Change number 0"))
        assertFalse(many.contains("Change number 99"))
    }

    @Test fun bulletsThatRepeatTheParagraphAreDropped() {
        // What Gemini Nano wrote for v0.1.1 with the first version of the prompt.
        val first = "This update focuses on improving build and release management processes. We have refined the release notes summaries and debug build names. Additionally, the release key script has been updated.\n\n" +
            "*   Release notes summaries have been refined.\n*   Debug build names have been updated.\n*   The release key script has been modified."
        assertEquals("This update focuses on improving build and release management processes. We have refined the release notes summaries and debug build names. Additionally, the release key script has been updated.",
            cleanReleaseSummary(first))
    }

    @Test fun releaseSummariesLoseLinksHeadingsAndPreambles() {
        val cleaned = cleanReleaseSummary("Here are the release notes:\n## Highlights\nYou can now install builds straight from workflow runs.\n- See https://example.com\n- Debug builds show the release they follow.")
        assertEquals("You can now install builds straight from workflow runs.\n- Debug builds show the release they follow.", cleaned)
        assertNull(cleanReleaseSummary("  "))
    }

    @Test fun releaseSummaryUsesTheChangesItIsGiven() = runBlocking {
        var seen = ""
        val model = FakeModel { prompt -> seen = prompt; "You can now install builds in one tap from a workflow run." }
        assertEquals("You can now install builds in one tap from a workflow run.", draftReleaseSummary(model, "v0.2.0", releaseChanges(githubNotes)))
        assertTrue(seen.contains("1. Install builds from workflow runs\n"))
        assertFalse(seen.contains("https://"))
        assertFalse(seen.contains("@dev"))
        assertNull(draftReleaseSummary(model, "v0.2.0", emptyList()))
    }
}
