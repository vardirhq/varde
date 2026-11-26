package no.vardir.varde

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GitHubClientTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString()
    private val github = GitHub("id", apiBase = base, webBase = base)

    @After fun stop() = server.shutdown()

    @Test fun longLogsKeepTheEndWhereErrorsAre() = runBlocking {
        val log = (1..5000).joinToString("\n") { "step $it ok" } + "\nError: the build failed\n"
        server.enqueue(MockResponse().setBody(log))
        val text = github.jobLog("repos/o/r/actions/jobs/1/logs", "t", keepBytes = 1000)
        assertTrue(text.startsWith("[Showing the last 1 KB of the log"))
        assertTrue(text.contains("Error: the build failed"))
        assertFalse(text.contains("step 1 ok\n"))
        assertFalse(text.contains("\\n"))
    }

    @Test fun shortLogsAreUnchanged() = runBlocking {
        server.enqueue(MockResponse().setBody("one\ntwo\n"))
        assertEquals("one\ntwo\n", github.jobLog("x/logs", "t"))
    }

    @Test fun rateLimitsAreRecognized() {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0").setHeader("X-RateLimit-Reset", "1700000000")
            .setBody("""{"message":"API rate limit exceeded"}"""))
        val e = assertThrows(RateLimited::class.java) { runBlocking { github.get("user", "t") } }
        assertEquals(1_700_000_000_000L, e.resetAt)
        assertTrue(e.message!!.contains("rate limit"))
        assertTrue(canUseSavedCopy(e))
    }

    @Test fun permissionErrorsAreNotRateLimits() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Resource not accessible by integration"}"""))
        val e = assertThrows(HttpFailure::class.java) { runBlocking { github.get("repos/o/r", "t") } }
        assertFalse(e is RateLimited)
        assertTrue(e.message!!.contains("Resource not accessible"))
        assertFalse(canUseSavedCopy(e))
    }

    @Test fun serverErrorsMayFallBackToSavedCopies() {
        server.enqueue(MockResponse().setResponseCode(502))
        val e = assertThrows(HttpFailure::class.java) { runBlocking { github.get("repos/o/r", "t") } }
        assertEquals(502, e.code)
        assertTrue(canUseSavedCopy(e))
    }

    @Test fun expiredSessionsAreUnauthorized() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(Unauthorized::class.java) { runBlocking { github.get("user", "t") } }
    }

    @Test fun listsFollowNextLinks() = runBlocking {
        server.enqueue(MockResponse().setBody("[1,2]").setHeader("Link", "<${base}items?page=2>; rel=\"next\", <${base}items?page=3>; rel=\"last\""))
        server.enqueue(MockResponse().setBody("[3,4]").setHeader("Link", "<${base}items?page=3>; rel=\"next\""))
        server.enqueue(MockResponse().setBody("[5]"))
        val all = github.getAll("items?page=1", "t")
        assertEquals("[1,2,3,4,5]", all.body)
        assertFalse(all.truncated)
        assertEquals("/items?page=2", server.takeRequest().let { server.takeRequest().path })
    }

    @Test fun listsStopAtTheLimitAndSaySo() = runBlocking {
        server.enqueue(MockResponse().setBody("[1,2,3]").setHeader("Link", "<${base}items?page=2>; rel=\"next\""))
        val all = github.getAll("items", "t", maxItems = 2)
        assertEquals("[1,2]", all.body)
        assertTrue(all.truncated)
    }

    @Test fun nextLinksOnOtherHostsAreIgnored() {
        assertNull(nextPage("<https://evil.example/x?page=2>; rel=\"next\"", "https://api.github.com/"))
        assertEquals("repos/o/r/issues?page=2", nextPage("<https://api.github.com/repos/o/r/issues?page=2>; rel=\"next\"", "https://api.github.com/"))
    }

    @Test fun graphqlErrorsBecomeReadableFailures() {
        server.enqueue(MockResponse().setBody("""{"errors":[{"message":"Pull request is not a draft"}]}"""))
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { github.graphql("mutation{}", org.json.JSONObject(), "t", "Marking it ready") }
        }
        assertEquals("Marking it ready failed: Pull request is not a draft", e.message)
    }

    @Test fun writesExplainRefusals() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Validation Failed"}"""))
        val e = assertThrows(HttpFailure::class.java) { runBlocking { github.send("POST", "repos/o/r/releases", "t", "Creating the release") } }
        assertEquals("Creating the release failed (HTTP 422): Validation Failed", e.message)
    }
}
