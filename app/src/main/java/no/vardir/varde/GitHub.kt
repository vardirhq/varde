package no.vardir.varde

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import android.os.SystemClock

data class DeviceCode(val code: String, val userCode: String, val interval: Long, val expires: Long)
data class Repo(val id: Long, val name: String, val owner: String, val description: String, val archived: Boolean, val privateRepo: Boolean, val url: String)
class Unauthorized : Exception("GitHub session expired. Sign in again.")
class GitHub(private val clientId: String) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private suspend fun request(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { response ->
            if (response.code == 401) throw Unauthorized()
            check(response.isSuccessful) { "GitHub request failed (HTTP ${response.code})" }
            response.body?.string() ?: error("Empty GitHub response")
        }
    }
    private suspend fun post(path: String, fields: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { fields.forEach { (k,v) -> add(k,v) } }.build()
        return JSONObject(request(Request.Builder().url("https://github.com/login/$path").header("Accept","application/json").post(form).build()))
    }
    suspend fun start(privateAccess: Boolean): DeviceCode {
        check(clientId.isNotBlank()) { "This build has no GitHub client ID." }
        val data = post("device/code", mapOf("client_id" to clientId, "scope" to if (privateAccess) "repo read:org" else "read:org"))
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
    internal suspend fun get(path: String, token: String): String = request(Request.Builder()
        .url("https://api.github.com/$path").header("Authorization","Bearer $token")
        .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").build())
    internal suspend fun jobLog(path: String, token: String): String = withContext(Dispatchers.IO) {
        // OkHttp strips the Authorization header when GitHub redirects to a different host.
        val client = http.newBuilder().followRedirects(true).followSslRedirects(false).build()
        val req = Request.Builder().url("https://api.github.com/$path")
            .header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { response ->
            if (response.code == 401) throw Unauthorized()
            check(response.isSuccessful) { "Unable to load job log (HTTP ${response.code})" }
            val source = response.body?.source() ?: error("Empty job log")
            val limit = 200_000L
            source.request(limit + 1)
            val truncated = source.buffer.size > limit
            source.readUtf8(minOf(source.buffer.size, limit)) + if (truncated) "\\n\\n[Log truncated]" else ""
        }
    }
    suspend fun login(token: String): String = JSONObject(get("user",token)).getString("login")
    suspend fun assignedWork(token: String): List<AttentionItem> {
        val data = JSONArray(get("issues?filter=assigned&state=open&per_page=100", token))
        return (0 until data.length()).map { index ->
            val item = data.getJSONObject(index)
            AttentionItem(
                title = item.getString("title"),
                repository = item.getJSONObject("repository").getString("full_name"),
                repositoryId = item.getJSONObject("repository").getLong("id"),
                kind = if (item.has("pull_request")) "Pull request" else "Issue",
                url = item.getString("html_url")
            )
        }
    }
    suspend fun repositories(token: String): List<Repo> {
        val result = mutableListOf<Repo>()
        var page = 1
        do {
            val data = JSONArray(get("user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member&page=$page",token))
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                result.add(Repo(item.getLong("id"),item.getString("name"),item.getJSONObject("owner").getString("login"),
                    if (item.isNull("description")) "" else item.optString("description"),item.optBoolean("archived"),item.optBoolean("private"),item.getString("html_url")))
            }
            page++
        } while (data.length() == 100)
        return result.distinctBy { it.id }
    }
}
