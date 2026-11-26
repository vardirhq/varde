package no.vardir.varde

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Work handed to an agent from Varde. [id] doubles as the tracking ID written into the brief; the agent is asked to put it
 * in its pull request description so the PR can be found with GitHub search. When the handover was about an existing pull
 * request, [pullNumber] is known up front and [startSha] is its head commit at handover time.
 */
internal data class Delegation(
    val id: String,
    val repoId: Long,
    val repository: String,
    val title: String,
    val createdAt: String,
    val pullNumber: Int? = null,
    val startSha: String = "",
    val done: Boolean = false,
)

internal enum class DelegationStage(val label: String, val tone: StatusTone) {
    Waiting("Waiting for agent", StatusTone.Neutral),
    InProgress("In progress", StatusTone.Pending),
    ChecksFailing("Checks failing", StatusTone.Failure),
    ReadyForReview("Ready for review", StatusTone.Success),
    Merged("Merged", StatusTone.Neutral),
    Closed("Closed", StatusTone.Neutral),
    Unknown("Status unknown", StatusTone.Neutral);

    /** Stages where the next step is yours. */
    val needsYou get() = this == ChecksFailing || this == ReadyForReview
    val finished get() = this == Merged || this == Closed
}

internal data class DelegationStatus(val stage: DelegationStage, val detail: String, val pull: PullRequest? = null)

private val TrackingId = Regex("varde[0-9a-z]{8}")

internal fun newTrackingId(random: java.util.Random = java.security.SecureRandom()): String =
    "varde" + (1..8).map { "0123456789abcdefghijklmnopqrstuvwxyz"[random.nextInt(36)] }.joinToString("")

internal fun isTrackingId(value: String) = TrackingId.matches(value)

/** Appended to a tracked brief. Alphanumeric only, so GitHub search treats it as one word. */
internal fun trackingInstruction(id: String): String =
    "\n## Tracking\nTracking ID: $id\nInclude this tracking ID in the description of the pull request you open, so progress can be followed.\n"

/** What GitHub currently says about a delegation, from its pull request (null when none is found) and that PR's checks. */
internal fun delegationStatus(d: Delegation, pr: PullRequest?, checks: CheckSummary?): DelegationStatus {
    if (pr == null) return DelegationStatus(DelegationStage.Waiting,
        if (d.pullNumber == null) "No pull request with tracking ID ${d.id} yet." else "Pull request #${d.pullNumber} not loaded.")
    fun s(stage: DelegationStage, detail: String) = DelegationStatus(stage, detail, pr)
    return when {
        pr.merged -> s(DelegationStage.Merged, "#${pr.number} was merged.")
        !pr.open -> s(DelegationStage.Closed, "#${pr.number} was closed without merging.")
        d.startSha.isNotBlank() && pr.headSha == d.startSha -> s(DelegationStage.Waiting, "No new commits on #${pr.number} since the handover.")
        checks == null -> s(DelegationStage.InProgress, "#${pr.number} is open; checks are unavailable.")
        checks.failed > 0 -> s(DelegationStage.ChecksFailing, "#${pr.number}: ${checks.failed} of ${checks.total} checks failing.")
        checks.pending > 0 -> s(DelegationStage.InProgress, "#${pr.number}: ${checks.pending} checks running.")
        pr.draft -> s(DelegationStage.InProgress, "#${pr.number} is still a draft.")
        else -> s(DelegationStage.ReadyForReview, "#${pr.number} is open" + if (checks.total == 0) " with no checks." else " and checks passed.")
    }
}

/**
 * Looks up a delegation on GitHub. Returns the delegation (with [Delegation.pullNumber] filled in once a PR is found)
 * and its status. Failures become [DelegationStage.Unknown] rather than "waiting", so missing data is never shown as progress.
 */
internal suspend fun checkDelegation(d: Delegation, repo: Repo?, load: suspend (String) -> CachedResponse): Pair<Delegation, DelegationStatus> {
    if (repo == null) return d to DelegationStatus(DelegationStage.Unknown, "${d.repository} is hidden or unavailable.")
    return try {
        var current = d
        if (current.pullNumber == null) {
            val query = "repo:${repo.owner}/${repo.name} is:pr ${d.id}"
            val found = JSONObject(load("search/issues?q=" + pathPart(query) + "&per_page=10").body).optJSONArray("items") ?: JSONArray()
            val number = (0 until found.length()).mapNotNull { found.optJSONObject(it)?.optInt("number")?.takeIf { n -> n > 0 } }.maxOrNull()
                ?: return current to delegationStatus(current, null, null)
            current = current.copy(pullNumber = number)
        }
        val prResponse = load(repoApi(repo) + "/pulls/" + current.pullNumber)
        val pr = parsePullRequest(prResponse.body)
        val checks = if (pr.headSha.isBlank() || !pr.open) null else runCatching {
            val result = load(repoApi(repo) + "/commits/" + pathPart(pr.headSha) + "/check-runs?per_page=100")
            parseCheckRuns(result.body, result.cached)
        }.getOrElse { if (it is CancellationException) throw it else null }
        val status = delegationStatus(current, pr, checks)
        current to if (prResponse.cached) status.copy(detail = status.detail + " (cached)") else status
    } catch (e: CancellationException) { throw e }
    catch (e: Unauthorized) { throw e }
    catch (_: Exception) { d to DelegationStatus(DelegationStage.Unknown, "Couldn't check GitHub for this delegation.") }
}

internal fun encodeDelegations(items: List<Delegation>): String = JSONObject().put("version", 1).put("delegations", JSONArray().apply {
    items.forEach { d ->
        put(JSONObject().put("id", d.id).put("repoId", d.repoId).put("repository", d.repository).put("title", d.title)
            .put("createdAt", d.createdAt).put("pullNumber", d.pullNumber ?: JSONObject.NULL).put("startSha", d.startSha).put("done", d.done))
    }
}).toString()

internal fun decodeDelegations(raw: String): List<Delegation> {
    val root = JSONObject(raw)
    require(root.getInt("version") == 1) { "Unsupported delegation data" }
    val array = root.getJSONArray("delegations")
    return (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        Delegation(o.getString("id"), o.getLong("repoId"), o.getString("repository"), o.getString("title"), o.optString("createdAt"),
            if (o.isNull("pullNumber")) null else o.optInt("pullNumber").takeIf { it > 0 }, o.optString("startSha"), o.optBoolean("done"))
    }.also { list -> require(list.all { isTrackingId(it.id) } && list.map { it.id }.distinct().size == list.size) { "Invalid delegation data" } }
}
