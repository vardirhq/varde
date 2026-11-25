package no.vardir.varde

import org.json.JSONArray
import org.json.JSONObject

/** The fields of GitHub's pull request detail response that the review screen uses. */
internal data class PullRequest(
    val number: Int,
    val title: String,
    val body: String,
    val state: String,
    val draft: Boolean,
    val merged: Boolean,
    val author: String,
    val headRef: String,
    val headSha: String,
    val baseRef: String,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val commits: Int,
    val comments: Int,
    val reviewComments: Int,
    /** GitHub computes mergeability lazily; null means not known yet. */
    val mergeable: Boolean?,
    val mergeableState: String,
    val updatedAt: String,
    val htmlUrl: String,
) {
    val open get() = state == "open" && !merged
    val status get() = pullState(state, draft, merged)
}

internal fun parsePullRequest(body: String): PullRequest {
    val o = JSONObject(body)
    val head = o.optJSONObject("head")
    val base = o.optJSONObject("base")
    return PullRequest(
        number = o.optInt("number"),
        title = jsonText(o, "title"),
        body = jsonText(o, "body"),
        state = jsonText(o, "state"),
        draft = o.optBoolean("draft"),
        merged = o.optBoolean("merged"),
        author = o.optJSONObject("user")?.optString("login").orEmpty(),
        headRef = head?.let { jsonText(it, "ref") }.orEmpty(),
        headSha = head?.let { jsonText(it, "sha") }.orEmpty(),
        baseRef = base?.let { jsonText(it, "ref") }.orEmpty(),
        additions = o.optInt("additions"),
        deletions = o.optInt("deletions"),
        changedFiles = o.optInt("changed_files"),
        commits = o.optInt("commits"),
        comments = o.optInt("comments"),
        reviewComments = o.optInt("review_comments"),
        mergeable = if (o.has("mergeable") && !o.isNull("mergeable")) o.optBoolean("mergeable") else null,
        mergeableState = jsonText(o, "mergeable_state"),
        updatedAt = jsonText(o, "updated_at"),
        htmlUrl = jsonText(o, "html_url"),
    )
}

/** Check runs for one commit. [truncated] is true when GitHub has more than were returned. */
internal data class CheckSummary(
    val total: Int, val passed: Int, val failed: Int, val pending: Int, val truncated: Boolean, val cached: Boolean,
)

internal fun parseCheckRuns(body: String, cached: Boolean): CheckSummary {
    val o = JSONObject(body)
    val runs = o.optJSONArray("check_runs") ?: JSONArray()
    val statuses = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
        .map { ciStatus(jsonText(it, "status"), jsonText(it, "conclusion")).tone }
    return CheckSummary(
        total = statuses.size,
        passed = statuses.count { it == StatusTone.Success },
        failed = statuses.count { it == StatusTone.Failure },
        pending = statuses.count { it == StatusTone.Pending },
        truncated = o.optInt("total_count") > statuses.size,
        cached = cached,
    )
}

internal data class Verdict(val status: StatusLabel, val detail: String, val readyToMerge: Boolean)

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/**
 * One-line answer to "can this be merged?", based only on what GitHub reported.
 * [checks] is null while loading or when check runs could not be read.
 */
internal fun pullVerdict(pr: PullRequest, checks: CheckSummary?): Verdict {
    fun v(tone: StatusTone, label: String, detail: String, ready: Boolean = false) = Verdict(StatusLabel(tone, label), detail, ready)
    val checkLine = checks?.let {
        listOf(plural(it.total, "check"), "${it.passed} passed", "${it.failed} failed", "${it.pending} running")
            .joinToString(" · ") + (if (it.truncated) " · more on GitHub" else "") + (if (it.cached) " · cached" else "")
    } ?: "Check runs unavailable"
    return when {
        pr.merged -> v(StatusTone.Neutral, "Merged", "${pr.headRef} was merged into ${pr.baseRef}.")
        !pr.open -> v(StatusTone.Neutral, "Closed", "This pull request was closed without merging.")
        checks != null && checks.failed > 0 -> v(StatusTone.Failure, "${plural(checks.failed, "check")} failing", checkLine)
        pr.mergeableState == "dirty" || pr.mergeable == false -> v(StatusTone.Failure, "Merge conflicts", "Resolve conflicts with ${pr.baseRef} first.")
        pr.draft -> v(StatusTone.Neutral, "Draft", "Mark it ready for review on GitHub before merging. $checkLine")
        checks == null -> v(StatusTone.Neutral, "Checks unknown", checkLine)
        checks.pending > 0 -> v(StatusTone.Pending, "${plural(checks.pending, "check")} running", checkLine)
        pr.mergeableState == "behind" -> v(StatusTone.Pending, "Behind ${pr.baseRef}", "Update the branch before merging. $checkLine")
        pr.mergeableState == "blocked" -> v(StatusTone.Pending, "Blocked", "Branch protection requires something else, such as an approving review. $checkLine")
        checks.total == 0 -> v(StatusTone.Neutral, "No checks", "No check runs were reported for the head commit.", ready = pr.mergeable == true)
        else -> v(StatusTone.Success, "Ready to merge", checkLine, ready = pr.mergeable != false)
    }
}
