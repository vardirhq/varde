package no.vardir.varde

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubWritesTest {
    private val repo = Repo(1, "demo", "owner", "", false, false, "https://github.com/owner/demo")
    private val sha = "0123456789012345678901234567890123456789"

    private fun json(action: WriteAction): JSONObject {
        val request = buildWriteRequest(repo, action, "token")
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
    @Test fun commentUsesIssueConversationEndpoint() {
        val action = WriteAction(WriteKind.COMMENT, 12, "hello")
        val request = buildWriteRequest(repo, action, "token")
        assertEquals("POST", request.method)
        assertEquals("/repos/owner/demo/issues/12/comments", request.url.encodedPath)
        assertEquals("hello", json(action).getString("body"))
    }
    @Test fun reviewsTargetTheReviewedCommit() {
        val request = json(WriteAction(WriteKind.REQUEST_CHANGES, 4, "Fix CI", sha))
        assertEquals("REQUEST_CHANGES", request.getString("event"))
        assertEquals(sha, request.getString("commit_id"))
        assertEquals("Fix CI", request.getString("body"))
    }
    @Test fun mergeUsesExpectedHeadAndSelectedMethod() {
        val action = WriteAction(WriteKind.MERGE, 7, headSha = sha, mergeMethod = "squash")
        assertEquals("PUT", buildWriteRequest(repo, action, "token").method)
        assertEquals(sha, json(action).getString("sha"))
        assertEquals("squash", json(action).getString("merge_method"))
    }
    @Test fun rejectsInvalidOrUnanchoredActions() {
        assertThrows(IllegalArgumentException::class.java) { buildWriteRequest(repo, WriteAction(WriteKind.COMMENT, 1, " "), "x") }
        assertThrows(IllegalArgumentException::class.java) { buildWriteRequest(repo, WriteAction(WriteKind.REQUEST_CHANGES, 1, "", sha), "x") }
        assertThrows(IllegalArgumentException::class.java) { buildWriteRequest(repo, WriteAction(WriteKind.MERGE, 1), "x") }
        assertThrows(IllegalArgumentException::class.java) { buildWriteRequest(repo, WriteAction(WriteKind.MERGE, 1, headSha = sha, mergeMethod = "invalid"), "x") }
    }
    @Test fun rerunFailureIncludesGitHubReasonAndHint() {
        val message = rerunFailureMessage(403, """{"message":"Unable to re-run this workflow run because it was created over a month ago"}""")
        assertTrue(message.contains("HTTP 403"))
        assertTrue(message.contains("created over a month ago"))
        assertTrue(message.contains("write access"))
        assertEquals("GitHub refused the rerun (HTTP 500). Refresh and try again.", rerunFailureMessage(500, "not json"))
    }
}
