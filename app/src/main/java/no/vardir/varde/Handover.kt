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
