package no.vardir.varde

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DeviceCode(val code: String, val userCode: String, val interval: Long, val expires: Long)
data class Repo(
    val id: Long, val name: String, val owner: String, val description: String, val archived: Boolean, val privateRepo: Boolean,
    val url: String, val defaultBranch: String = "",
)
class Unauthorized : Exception("GitHub session expired. Sign in again.")

/** GitHub answered with an error status. [code] is the HTTP status. */
internal open class HttpFailure(val code: Int, message: String) : IllegalStateException(message)

/** The account's GitHub rate limit is used up. [resetAt] is epoch milliseconds when known. */
internal class RateLimited(val resetAt: Long?) : HttpFailure(429, rateLimitMessage(resetAt))

internal fun rateLimitMessage(resetAt: Long?): String = "GitHub's rate limit for your account is used up" +
    (resetAt?.let { ". It resets at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) + "." }
        ?: ". Try again in a few minutes.")

internal data class ConditionalResponse(val notModified: Boolean, val body: String, val etag: String)

/** One page of a list response and the path of the next page, if any. */
internal data class Page(val body: String, val next: String?)

/** A list gathered from several pages. [truncated] is true when more items exist than were fetched. */
internal data class Gathered(val body: String, val truncated: Boolean)

/** The `rel="next"` URL of a GitHub `Link` header, relative to [apiBase]. */
internal fun nextPage(link: String?, apiBase: String): String? = link?.split(',')?.map { it.trim() }
    ?.firstOrNull { it.endsWith("rel=\"next\"") }?.substringAfter('<')?.substringBefore('>')
    ?.takeIf { it.startsWith(apiBase) }?.removePrefix(apiBase)

/** Turns an error response into the exception callers handle: session expiry, rate limits, or a readable failure. */
internal fun httpFailure(code: Int, headers: (String) -> String?, body: String, what: String = "GitHub request"): Exception {
    if (code == 401) return Unauthorized()
    val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
    val limited = (code == 403 || code == 429) &&
        (headers("X-RateLimit-Remaining") == "0" || headers("Retry-After") != null || message.contains("rate limit", ignoreCase = true))
    if (limited) {
        val reset = headers("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
            ?: headers("Retry-After")?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 }
        return RateLimited(reset)
    }
    val reason = message.replace(Regex("\\s+"), " ").trim().take(200)
    return HttpFailure(code, "$what failed (HTTP $code)" + if (reason.isNotBlank()) ": $reason" else ".")
}

class GitHub(private val clientId: String, private val apiBase: String = "https://api.github.com/", private val webBase: String = "https://github.com/") {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    // Logs and raw files redirect to storage hosts; OkHttp drops the Authorization header on cross-host redirects.
    private val redirecting = http.newBuilder().followRedirects(true).followSslRedirects(false).build()

    private fun api(path: String, token: String, accept: String = "application/vnd.github+json") = Request.Builder().url(apiBase + path)
        .header("Authorization", "Bearer $token").header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28")

    private fun Response.failure(what: String = "GitHub request"): Exception = httpFailure(code, { header(it) }, body?.string().orEmpty(), what)

    private suspend fun <T> call(request: Request, client: OkHttpClient = http, what: String = "GitHub request", read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 304) throw response.failure(what)
                read(response)
            }
        }

    /** A write whose outcome is unknown if the connection drops after sending. */
    private suspend fun <T> write(request: Request, what: String, read: (Response) -> T): T = try {
        call(request, what = what, read = read)
    } catch (e: java.io.IOException) { throw WriteOutcomeUnknown(e) }

    private suspend fun post(path: String, fields: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return call(Request.Builder().url(webBase + "login/$path").header("Accept", "application/json").post(form).build()) {
            JSONObject(it.body?.string() ?: error("Empty GitHub response"))
        }
    }
    suspend fun start(privateAccess: Boolean, publicWrite: Boolean): DeviceCode {
        check(clientId.isNotBlank()) { "This build has no GitHub client ID." }
        val data = post("device/code", mapOf("client_id" to clientId, "scope" to if (privateAccess) "repo read:org" else if (publicWrite) "public_repo read:org" else "read:org"))
        return DeviceCode(data.getString("device_code"), data.getString("user_code"), data.optLong("interval",5).coerceAtLeast(5), data.getLong("expires_in"))
    }
    suspend fun authorize(code: DeviceCode, onConnectionProblem: () -> Unit = {}, onReconnected: () -> Unit = {}): String {
        var interval = code.interval
        val deadline = SystemClock.elapsedRealtime() + code.expires * 1000
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(interval * 1000)
            if (SystemClock.elapsedRealtime() >= deadline) break
            val data = try {
                retryNetworkUntil(deadline, interval * 1000, SystemClock::elapsedRealtime, { delay(it) }, onConnectionProblem) {
                    post("oauth/access_token", mapOf("client_id" to clientId,"device_code" to code.code,"grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
                }.also { onReconnected() }
            } catch (e: java.io.IOException) {
                throw java.io.IOException("Couldn't reach GitHub before the code expired. Check your connection and try again.", e)
            }
            when (data.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> { interval += 5; continue }
                "access_denied" -> error("Authorization denied.")
                "expired_token" -> error("Code expired. Try again.")
                "" -> return data.getString("access_token")
                else -> error("GitHub authorization failed.")
            }
        }
        error("Code expired. Try again.")
    }

    internal suspend fun get(path: String, token: String): String = call(api(path, token).build()) { it.body?.string() ?: error("Empty GitHub response") }

    internal suspend fun page(path: String, token: String): Page = call(api(path, token).build()) {
        Page(it.body?.string() ?: error("Empty GitHub response"), nextPage(it.header("Link"), apiBase))
    }

    /**
     * Follows `Link: rel="next"` until [maxItems] items are gathered and returns one JSON array. Lists wrapped in an
     * object (workflow runs, check runs) are not supported; use [get] for those.
     */
    internal suspend fun getAll(path: String, token: String, maxItems: Int = 1000): Gathered {
        val items = JSONArray()
        var next: String? = path
        while (next != null && items.length() < maxItems) {
            val page = page(next, token)
            val array = JSONArray(page.body)
            for (i in 0 until array.length()) if (items.length() < maxItems) items.put(array.get(i))
            next = page.next
        }
        return Gathered(items.toString(), next != null)
    }

    /** GET with If-None-Match. A 304 reply has no body and doesn't count against GitHub's primary rate limit. */
    internal suspend fun getConditional(path: String, token: String, etag: String?): ConditionalResponse {
        val builder = api(path, token)
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        return call(builder.build()) { response ->
            if (response.code == 304) ConditionalResponse(notModified = true, body = "", etag = etag.orEmpty())
            else ConditionalResponse(false, response.body?.string() ?: error("Empty GitHub response"), response.header("ETag").orEmpty())
        }
    }

    internal suspend fun submitWrite(repo: Repo, action: WriteAction, token: String) {
        val request = buildWriteRequest(repo, action, token, apiBase)
        try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val failure = response.failure()
                        if (failure is Unauthorized || failure is RateLimited) throw failure
                        throw IllegalStateException(when (response.code) {
                            403 -> "GitHub denied this action. Check repository permissions and branch rules. If needed, sign out and reconnect with write access."
                            404 -> "GitHub could not access this item. Check repository permissions and token scopes."
                            405, 409 -> "GitHub rejected the action because the pull request changed or cannot be merged. Refresh and review it again." +
                                (failure.message?.substringAfter(": ", "")?.takeIf { it.isNotBlank() }?.let { " GitHub says: \"$it\"" } ?: "")
                            422 -> "GitHub could not accept this action. Check the review, branch rules, and merge method."
                            else -> "GitHub rejected the action (HTTP ${response.code})."
                        })
                    }
                    if (action.kind == WriteKind.MERGE) {
                        val body = response.body?.string().orEmpty()
                        check(runCatching { JSONObject(body).optBoolean("merged", false) }.getOrDefault(false)) {
                            "GitHub did not confirm the merge. Refresh the pull request before trying again."
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw WriteOutcomeUnknown(e)
        }
    }

    /** JSON write to [path]; returns the response body. [method] is POST, PUT, PATCH or DELETE. */
    internal suspend fun send(method: String, path: String, token: String, what: String, json: JSONObject? = null): String {
        val body = json?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
            ?: if (method == "DELETE") null else ByteArray(0).toRequestBody(null)
        return write(api(path, token).method(method, body).build(), what) { it.body?.string().orEmpty() }
    }

    /** A GraphQL request; GraphQL reports errors in the body, which become a readable failure. */
    internal suspend fun graphql(query: String, variables: JSONObject, token: String, what: String): JSONObject {
        val response = JSONObject(send("POST", "graphql", token, what, JSONObject().put("query", query).put("variables", variables)))
        response.optJSONArray("errors")?.optJSONObject(0)?.let { error ->
            throw IllegalStateException("$what failed: " + jsonText(error, "message", "GitHub reported an error.").take(200))
        }
        return response.optJSONObject("data") ?: JSONObject()
    }

    internal suspend fun createIssue(repo: Repo, title: String, body: String, token: String): String {
        val response = write(buildCreateIssueRequest(repo, title, body, token, apiBase), "Creating the issue") { it.body?.string().orEmpty() }
        val url = runCatching { JSONObject(response).optString("html_url") }.getOrDefault("")
        if (!validWorkLink(url) || url.isBlank()) throw WriteOutcomeUnknown(IllegalStateException("Invalid issue response"))
        return url
    }
    internal suspend fun rerunFailedJobs(repo: Repo, runId: Long, token: String) {
        val request = api(repoApi(repo) + "/actions/runs/$runId/rerun-failed-jobs", token).post(ByteArray(0).toRequestBody(null)).build()
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) throw Unauthorized()
                check(response.isSuccessful) { rerunFailureMessage(response.code, response.body?.string().orEmpty()) }
            }
        }
    }
    /** Permanently deletes a completed workflow run and its logs. */
    internal suspend fun deleteRun(repo: Repo, runId: Long, token: String) =
        actionsWrite(repoApi(repo) + "/actions/runs/$runId", "DELETE", token, "delete this run")
    /** Stops a workflow from running on any trigger until it's enabled again on GitHub. */
    internal suspend fun disableWorkflow(repo: Repo, workflowId: Long, token: String) =
        actionsWrite(repoApi(repo) + "/actions/workflows/$workflowId/disable", "PUT", token, "disable this workflow")
    internal suspend fun actionsWrite(path: String, method: String, token: String, what: String, json: JSONObject? = null) {
        val body = json?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
            ?: if (method == "DELETE") null else ByteArray(0).toRequestBody(null)
        val request = api(path, token).method(method, body).build()
        try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    if (response.code == 401) throw Unauthorized()
                    check(response.isSuccessful) { actionsWriteFailure(response.code, what, response.body?.string().orEmpty()) }
                }
            }
        } catch (e: java.io.IOException) { throw WriteOutcomeUnknown(e) }
    }

    /**
     * Streams a download to [dest], following GitHub's redirect to its storage host (which never receives the token).
     * Fails if it exceeds [maxBytes]. [onProgress] gets bytes so far and the total when known (else -1).
     */
    internal suspend fun download(path: String, token: String, accept: String, dest: java.io.File, maxBytes: Long, onProgress: (Long, Long) -> Unit) =
        call(api(path, token, accept).build(), redirecting, "Downloading") { response ->
            val body = response.body ?: error("Empty download")
            val total = body.contentLength()
            check(total <= maxBytes) { "This download is ${total / 1_000_000} MB; Varde stops at ${maxBytes / 1_000_000} MB." }
            dest.parentFile?.mkdirs()
            var done = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        done += read
                        check(done <= maxBytes) { "This download is over ${maxBytes / 1_000_000} MB, more than Varde downloads." }
                        output.write(buffer, 0, read)
                        onProgress(done, total)
                    }
                }
            }
        }

    /** Raw file bytes from the contents API (works for private repositories and files over 1 MB), capped at [maxBytes]. */
    internal suspend fun rawFile(path: String, token: String, maxBytes: Long): ByteArray =
        call(api(path, token, accept = "application/vnd.github.raw").build(), redirecting) { response ->
            val body = response.body ?: error("Empty GitHub response")
            check(body.contentLength() <= maxBytes) { "File too large to show." }
            val source = body.source()
            source.request(maxBytes + 1)
            val bytes = source.buffer.readByteArray(minOf(source.buffer.size, maxBytes + 1))
            check(bytes.size <= maxBytes) { "File too large to show." }
            bytes
        }

    /**
     * A job log. Failures are almost always at the end, so a long log keeps its last [keepBytes], starting at a whole line.
     * Reading stops after [maxDownload] bytes so a runaway log can't use unbounded data.
     */
    internal suspend fun jobLog(path: String, token: String, keepBytes: Long = 200_000, maxDownload: Long = 50_000_000): String =
        call(Request.Builder().url(apiBase + path).header("Authorization", "Bearer $token").build(), redirecting, "Loading the job log") { response ->
            val source = response.body?.source() ?: error("Empty job log")
            val buffer = okio.Buffer()
            var total = 0L
            while (total < maxDownload) {
                val read = source.read(buffer, 64 * 1024)
                if (read == -1L) break
                total += read
                if (buffer.size > keepBytes) buffer.skip(buffer.size - keepBytes)
            }
            val stopped = total >= maxDownload && !source.exhausted()
            val text = buffer.readUtf8()
            when {
                stopped -> "[This log is over ${maxDownload / 1_000_000} MB; showing the last part that was loaded. Open it on GitHub for the rest.]\n\n" + text.substringAfter('\n')
                total > keepBytes -> "[Showing the last ${keepBytes / 1000} KB of the log, where errors usually are.]\n\n" + text.substringAfter('\n')
                else -> text
            }
        }

    suspend fun login(token: String): String = JSONObject(get("user",token)).getString("login")

    /** Open issues and pull requests assigned to you; [Gathered.truncated] when there were more than [max]. */
    internal suspend fun assignedWork(token: String, max: Int = 300): Pair<List<AttentionItem>, Boolean> {
        val gathered = getAll("issues?filter=assigned&state=open&per_page=100", token, max)
        val data = JSONArray(gathered.body)
        return (0 until data.length()).map { index ->
            val item = data.getJSONObject(index)
            AttentionItem(
                title = item.getString("title"),
                repository = item.getJSONObject("repository").getString("full_name"),
                repositoryId = item.getJSONObject("repository").getLong("id"),
                kind = if (item.has("pull_request")) AttentionKind.AssignedPullRequest else AttentionKind.AssignedIssue,
                url = item.getString("html_url"),
                updatedAt = item.optString("updated_at")
            )
        } to gathered.truncated
    }
    suspend fun repositories(token: String): List<Repo> {
        val data = JSONArray(getAll("user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member", token, maxItems = 5000).body)
        return (0 until data.length()).map { i ->
            val item = data.getJSONObject(i)
            Repo(item.getLong("id"), item.getString("name"), item.getJSONObject("owner").getString("login"),
                if (item.isNull("description")) "" else item.optString("description"), item.optBoolean("archived"), item.optBoolean("private"),
                item.getString("html_url"), jsonText(item, "default_branch"))
        }.distinctBy { it.id }
    }
}
