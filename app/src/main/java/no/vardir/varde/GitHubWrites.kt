package no.vardir.varde

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class WriteOutcomeUnknown(cause: Throwable) :
    Exception("Connection interrupted. The action may have completed; close and refresh GitHub before trying again.", cause)

internal enum class WriteKind { COMMENT, APPROVE, REQUEST_CHANGES, MERGE }

internal data class WriteAction(
    val kind: WriteKind,
    val number: Int,
    val body: String = "",
    val headSha: String = "",
    val mergeMethod: String = "merge"
)

/** Build only valid, commit-anchored requests. GitHub remains the authority for permissions and branch rules. */
internal fun buildWriteRequest(repo: Repo, action: WriteAction, token: String): Request {
    require(action.number > 0)
    val base = "https://api.github.com/" + repoApi(repo)
    val payload = JSONObject()
    val url: String
    when (action.kind) {
        WriteKind.COMMENT -> {
            require(action.body.isNotBlank()) { "Enter a comment." }
            url = "$base/issues/${action.number}/comments"
            payload.put("body", action.body)
        }
        WriteKind.APPROVE, WriteKind.REQUEST_CHANGES -> {
            require(action.headSha.matches(Regex("[a-fA-F0-9]{40}"))) { "Refresh the pull request to review its current commit." }
            require(action.kind != WriteKind.REQUEST_CHANGES || action.body.isNotBlank()) { "Explain what needs to change." }
            url = "$base/pulls/${action.number}/reviews"
            payload.put("event", if (action.kind == WriteKind.APPROVE) "APPROVE" else "REQUEST_CHANGES")
            payload.put("commit_id", action.headSha)
            if (action.body.isNotBlank()) payload.put("body", action.body)
        }
        WriteKind.MERGE -> {
            require(action.headSha.matches(Regex("[a-fA-F0-9]{40}"))) { "Refresh the pull request to confirm its current commit." }
            require(action.mergeMethod in setOf("merge", "squash", "rebase"))
            url = "$base/pulls/${action.number}/merge"
            payload.put("sha", action.headSha)
            payload.put("merge_method", action.mergeMethod)
        }
    }
    val request = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    return if (action.kind == WriteKind.MERGE) request.put(body).build() else request.post(body).build()
}

internal fun buildCreateIssueRequest(repo: Repo, title: String, text: String, token: String): Request {
    require(title.isNotBlank())
    val payload = JSONObject().put("title", title).put("body", text).toString()
    return Request.Builder().url("https://api.github.com/" + repoApi(repo) + "/issues")
        .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
}

/** Explains a failed rerun, including GitHub's own reason (for example, runs older than a month can't be rerun). */
internal fun rerunFailureMessage(code: Int, body: String): String {
    val reason = runCatching { org.json.JSONObject(body).optString("message") }.getOrDefault("")
        .replace(Regex("\\s+"), " ").trim().take(200)
    val hint = when (code) {
        403 -> "If you signed in without write access, sign out and reconnect with it."
        404 -> "Check that you still have access to this repository."
        409 -> "The run may already be running again. Refresh to check."
        else -> "Refresh and try again."
    }
    return listOf("GitHub refused the rerun (HTTP $code).", reason.takeIf { it.isNotBlank() }?.let { "GitHub says: \"$it\"" }, hint)
        .filterNotNull().joinToString(" ")
}

/** Explains a refused Actions write, including GitHub's own message. */
internal fun actionsWriteFailure(code: Int, what: String, body: String): String {
    val reason = runCatching { org.json.JSONObject(body).optString("message") }.getOrDefault("").replace(Regex("\\s+"), " ").trim().take(200)
    val hint = when (code) {
        403 -> "This needs write access to the repository. If you signed in without it, sign out and reconnect with write access."
        404 -> "It may already be gone, or you no longer have access."
        409 -> "Runs that are still in progress can't be deleted; cancel or wait for it first."
        else -> "Refresh and try again."
    }
    return listOfNotNull("GitHub refused to $what (HTTP $code).", reason.takeIf { it.isNotBlank() }?.let { "GitHub says: \"$it\"" }, hint).joinToString(" ")
}
