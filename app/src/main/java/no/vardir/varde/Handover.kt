package no.vardir.varde

/** Keep the selected evidence explicit: a log excerpt is a lead, not a diagnosis. */
internal fun failureExcerpt(log: String, context: Int = 3, maxLines: Int = 55): String {
    val lines = log.lines()
    val indices = lines.indices.filter {
        val line = lines[it]
        line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) ||
            line.contains("exception", ignoreCase = true)
    }
    if (indices.isEmpty()) return ""
    val selected = sortedSetOf<Int>()
    for (index in indices) {
        for (i in (index - context).coerceAtLeast(0)..(index + context).coerceAtMost(lines.lastIndex)) {
            if (selected.size >= maxLines) break
            selected.add(i)
        }
        if (selected.size >= maxLines) break
    }
    return selected.joinToString("\n") { lines[it] }
}

internal fun buildHandover(
    repo: String, context: String, request: String, evidence: String, instructions: List<Pair<String, String>>
): String = buildString {
    appendLine("# Agent handover")
    appendLine()
    appendLine("Repository: $repo")
    appendLine("Context: $context")
    appendLine()
    appendLine("## Request")
    appendLine(request.ifBlank { "[Describe the task and acceptance criteria]" })
    if (evidence.isNotBlank()) {
        appendLine()
        appendLine("## Selected evidence")
        appendLine(evidence)
    }
    instructions.forEach { (name, value) ->
        appendLine()
        appendLine("## $name")
        appendLine(value)
    }
}

internal fun actionJobRoute(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "github.com") return null
    val match = Regex("""/[^/]+/[^/]+/actions/runs/(\d+)/job/(\d+)/?""").matchEntire(uri.path ?: "") ?: return null
    return "log:${match.groupValues[2]}:${match.groupValues[1]}"
}

internal fun hasFailedJobs(body: String): Boolean = runCatching {
    val jobs = org.json.JSONObject(body).optJSONArray("jobs") ?: return@runCatching false
    (0 until jobs.length()).any { jobs.optJSONObject(it)?.optString("conclusion") == "failure" }
}.getOrDefault(false)

/** Repository facts for a handover from the overview page. */
internal fun repositoryEvidence(body: String): String {
    val o = org.json.JSONObject(body)
    return listOfNotNull(
        "Repository: " + jsonText(o, "full_name") + " (" + jsonText(o, "html_url") + ")",
        "Default branch: " + jsonText(o, "default_branch", "unknown"),
        jsonText(o, "language").takeIf { it.isNotBlank() }?.let { "Main language: $it" },
        "Open issues and pull requests: " + o.optInt("open_issues_count"),
        jsonText(o, "description").takeIf { it.isNotBlank() }?.let { "Description: $it" },
    ).joinToString("\n")
}

/** Subject lines of recent commits, newest first. */
internal fun recentCommits(body: String): String {
    val a = org.json.JSONArray(body)
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.joinToString("\n") { c ->
        "- " + jsonText(c, "sha").take(7) + " " + (c.optJSONObject("commit")?.optString("message").orEmpty().lineSequence().firstOrNull().orEmpty()).take(120)
    }.ifBlank { "No commits returned." }
}

/** The issue itself, and a request to reference it so GitHub links the PR. */
internal fun issueEvidence(body: String): String {
    val o = org.json.JSONObject(body)
    val labels = o.optJSONArray("labels")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
    return listOfNotNull(
        "Issue: " + jsonText(o, "html_url"),
        "Title: " + jsonText(o, "title"),
        "State: " + jsonText(o, "state"),
        labels.takeIf { it.isNotEmpty() }?.let { "Labels: " + it.joinToString() },
        "Description:\n" + jsonText(o, "body", "No description").take(12000),
        "When you open a pull request for this, reference the issue (for example \"Fixes #${o.optInt("number")}\").",
    ).joinToString("\n")
}

/** Issue comments as "author: text", capped so long threads don't crowd out the rest of the brief. */
internal fun issueComments(body: String, maxChars: Int = 8000): String {
    val a = org.json.JSONArray(body)
    val out = StringBuilder()
    for (i in 0 until a.length()) {
        val c = a.optJSONObject(i) ?: continue
        val line = (c.optJSONObject("user")?.optString("login") ?: "someone") + ": " + jsonText(c, "body").take(1500).trim()
        if (out.length + line.length > maxChars) { out.append("[More comments on GitHub.]"); break }
        out.append(line).append("\n\n")
    }
    return out.toString().trim()
}
