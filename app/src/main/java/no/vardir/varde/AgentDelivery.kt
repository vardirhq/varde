package no.vardir.varde

import org.json.JSONArray
import org.json.JSONObject

/**
 * Coding agents that take work through GitHub itself. Claude responds to `@claude` where the repository has the Claude
 * GitHub app or action installed; Copilot's coding agent takes assigned issues and `@copilot` mentions in pull requests
 * where it's enabled. Varde can't see whether either is set up, so a missing setup shows up as no response, not an error.
 */
internal enum class AgentTarget(val label: String, val mention: String) { Claude("Claude", "@claude"), Copilot("Copilot", "@copilot") }

/** Where the brief goes: a comment on an existing issue or pull request, or a new issue. */
internal sealed interface AgentDelivery {
    val target: AgentTarget
    data class Comment(override val target: AgentTarget, val number: Int, val isPull: Boolean) : AgentDelivery
    /** Copilot on an issue: the brief is posted as a comment, then the issue is assigned to Copilot. */
    data class Assign(val number: Int) : AgentDelivery { override val target = AgentTarget.Copilot }
    data class NewIssue(override val target: AgentTarget) : AgentDelivery
}

/** GitHub rejects comment and issue bodies over 65,536 characters. */
internal const val MaxGitHubBody = 65_000

internal const val CopilotAssignee = "copilot-swe-agent[bot]"

/** The options that make sense on a page: the PR or issue it's about, or a new issue. */
internal fun agentDeliveries(pullNumber: Int?, issueNumber: Int?): List<AgentDelivery> = when {
    pullNumber != null -> AgentTarget.entries.map { AgentDelivery.Comment(it, pullNumber, isPull = true) }
    issueNumber != null -> listOf(AgentDelivery.Comment(AgentTarget.Claude, issueNumber, isPull = false), AgentDelivery.Assign(issueNumber))
    else -> AgentTarget.entries.map { AgentDelivery.NewIssue(it) }
}

internal fun AgentDelivery.label(): String = when (this) {
    is AgentDelivery.Comment -> "Ask ${target.mention} on #$number"
    is AgentDelivery.Assign -> "Assign #$number to Copilot"
    is AgentDelivery.NewIssue -> "Open an issue for ${target.label}"
}

/** What confirming will do, in full, for the confirmation dialog. */
internal fun AgentDelivery.explanation(repo: Repo): String = when (this) {
    is AgentDelivery.Comment -> "Posts the brief as a comment on ${if (isPull) "pull request" else "issue"} #$number in ${repo.owner}/${repo.name}, " +
        "starting with ${target.mention} so the agent picks it up."
    is AgentDelivery.Assign -> "Posts the brief as a comment on issue #$number in ${repo.owner}/${repo.name}, then assigns the issue to Copilot's coding agent."
    is AgentDelivery.NewIssue -> "Opens a new issue in ${repo.owner}/${repo.name} with the brief" +
        if (target == AgentTarget.Copilot) " and assigns it to Copilot's coding agent." else ", starting with ${target.mention} so the agent picks it up."
} + " Anyone who can see the repository's issues will see the brief. If the agent isn't set up for this repository, nothing will respond."

/** The text posted to GitHub. Mentions go first; Copilot assignments don't need one. */
internal fun agentBody(delivery: AgentDelivery, brief: String): String {
    val mention = delivery is AgentDelivery.Comment || (delivery is AgentDelivery.NewIssue && delivery.target == AgentTarget.Claude)
    return if (mention) "${delivery.target.mention}\n\n$brief" else brief
}

/** True when GitHub's issue response lists Copilot as an assignee; GitHub silently drops assignees it can't add. */
internal fun copilotAssigned(issueBody: String): Boolean {
    val assignees = JSONObject(issueBody).optJSONArray("assignees") ?: JSONArray()
    return (0 until assignees.length()).any { assignees.optJSONObject(it)?.optString("login")?.contains("copilot", ignoreCase = true) == true }
}

/** Sends the brief and returns a message for the person. */
internal suspend fun deliverToAgent(github: GitHubApi, repo: Repo, delivery: AgentDelivery, title: String, brief: String): String {
    val body = agentBody(delivery, brief)
    check(body.length <= MaxGitHubBody) { "The brief is ${body.length} characters; GitHub allows about $MaxGitHubBody. Shorten it or share it instead." }
    val base = repoApi(repo)
    suspend fun comment(number: Int) = github.send("POST", "$base/issues/$number/comments", "Posting the brief", JSONObject().put("body", body))
    suspend fun assignCopilot(number: Int) {
        val issue = github.send("POST", "$base/issues/$number/assignees", "Assigning Copilot", JSONObject().put("assignees", JSONArray().put(CopilotAssignee)))
        check(copilotAssigned(issue)) { "GitHub didn't assign Copilot to #$number. Copilot's coding agent may not be enabled for this repository or account." }
    }
    return when (delivery) {
        is AgentDelivery.Comment -> { comment(delivery.number); "Posted to #${delivery.number} for ${delivery.target.label}." }
        is AgentDelivery.Assign -> { comment(delivery.number); assignCopilot(delivery.number); "Assigned #${delivery.number} to Copilot with the brief." }
        is AgentDelivery.NewIssue -> {
            val created = JSONObject(github.send("POST", "$base/issues", "Opening the issue", JSONObject().put("title", title).put("body", body)))
            val number = created.optInt("number")
            if (delivery.target == AgentTarget.Copilot && number > 0) assignCopilot(number)
            "Opened #$number for ${delivery.target.label}."
        }
    }
}
