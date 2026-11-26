package no.vardir.varde

/*
 * Optional on-device assistance. Everything here is plain Kotlin over a small [TextModel] interface so it can be tested
 * without a device. Rules (docs/ROADMAP.md, item 7):
 * - The model only writes what you would otherwise type: a task with acceptance criteria, or a clearer review comment.
 * - Repository instructions (AGENTS.md, CLAUDE.md) and tracking IDs are never sent through the model; Varde adds them verbatim.
 * - For logs, the model only picks line numbers. The excerpt is cut from the real log, so it can't contain invented lines.
 * - Output is always shown for editing first; nothing generated is shared or sent to GitHub without confirmation.
 */

internal enum class ModelStatus { Unavailable, Downloadable, Downloading, Available }

internal sealed interface DownloadState {
    data object Started : DownloadState
    data class Progress(val bytes: Long) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** The on-device model, behind an interface so features can be tested with a fake. */
internal interface TextModel {
    suspend fun status(): ModelStatus
    /** Why the last [status] call reported [ModelStatus.Unavailable], if it failed rather than answered. */
    val statusError: Throwable? get() = null
    fun download(): kotlinx.coroutines.flow.Flow<DownloadState>
    /** Total tokens the model accepts for one request. */
    suspend fun tokenLimit(): Int
    suspend fun generate(prompt: String, maxOutputTokens: Int): String
}

/** A model failure with ML Kit's error code (see GenAiException.ErrorCode), kept free of ML Kit types. */
internal class AssistException(val code: Int?, message: String?) : Exception(message)

internal sealed interface AssistResult<out T> {
    data class Done<T>(val value: T) : AssistResult<T>
    data class Failed(val reason: String) : AssistResult<Nothing>
}

/** Explains a failure in words, so "it didn't work" comes with the reason and what to try. */
internal fun assistFailure(error: Throwable?): String {
    val code = (error as? AssistException)?.code
    val detail = error?.message?.takeIf { it.isNotBlank() }?.take(160)
    val reason = when (code) {
        -101 -> "This phone's AICore version doesn't work with Varde's ML Kit client. Update \"Google AICore\" and \"Private Compute Services\" in the Play Store, then try again."
        604 -> "Gemini Nano needs a system update on this phone. Check for Android and Google Play system updates."
        8 -> "Gemini Nano isn't available right now. It may still be setting up after a download or update."
        9 -> "The model is busy. Try again in a moment."
        27 -> "Varde has used its on-device AI allowance for now. Try again later."
        30 -> "On-device AI only runs while Varde is open on screen."
        12 -> "That's too much text for the on-device model."
        501 -> "Not enough free storage for the on-device model."
        null -> if (error == null) "The model returned nothing usable. Try rewording your request." else "On-device AI failed."
        else -> "On-device AI failed (error $code)."
    }
    return if (detail != null && code !in setOf(9, 27, 30)) "$reason\nDetails: $detail" else reason
}

/** Rough characters-per-token used to keep prompts inside the model's limit. Conservative for code and logs. */
private const val CharsPerToken = 3

/** Characters of variable input that fit, after the fixed prompt text and room for the answer. */
internal fun inputBudget(tokenLimit: Int, outputTokens: Int, fixedPromptChars: Int): Int =
    ((tokenLimit - outputTokens) * CharsPerToken - fixedPromptChars).coerceAtLeast(0)

private val TrackingIdLike = Regex("""\bvarde[0-9a-z]{8}\b""")

private fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max).trimEnd() + "\n[…]"

// ---- Brief drafting ----

internal data class BriefDraft(val task: String, val criteria: List<String>) {
    /** Text for the handover's request field; the rest of the brief is still assembled by Varde. */
    fun asRequest(): String = buildString {
        append(task)
        if (criteria.isNotEmpty()) {
            append("\n\nAcceptance criteria:\n")
            append(criteria.joinToString("\n") { "- $it" })
        }
    }
}

private const val BriefInstructions = """You help a developer hand a task to an AI coding agent.
Using only the request and context below, write:
TASK: one or two sentences saying what should change and why.
CRITERIA:
- two to five specific, checkable acceptance criteria, one per line.
Do not invent file names, functions, errors or facts that are not in the context.
Do not write about tools, tracking IDs, branches or repository rules.
"""

internal fun briefPrompt(request: String, context: String, budget: Int): String {
    val requestPart = clip(request.trim(), (budget / 4).coerceAtLeast(200))
    val contextPart = clip(context.trim(), (budget - requestPart.length).coerceAtLeast(0))
    return BriefInstructions + "\nREQUEST:\n" + requestPart + (if (contextPart.isBlank()) "" else "\n\nCONTEXT:\n$contextPart") + "\n"
}

/** Parses the model's answer. Returns null when nothing usable came back. Tracking-ID lookalikes are removed. */
internal fun parseBriefDraft(output: String): BriefDraft? {
    val text = output.replace("```", "").replace(TrackingIdLike, "").trim()
    if (text.isBlank()) return null
    val taskMatch = Regex("""(?is)TASK:\s*(.*?)(?=\n\s*CRITERIA:|\z)""").find(text)
    val criteriaBlock = Regex("""(?is)CRITERIA:\s*(.*)""").find(text)?.groupValues?.get(1).orEmpty()
    val task = (taskMatch?.groupValues?.get(1) ?: if (criteriaBlock.isBlank()) text else "")
        .lines().filterNot { it.trim().startsWith("tracking", ignoreCase = true) }.joinToString("\n").trim().take(600)
    val criteria = criteriaBlock.lines()
        .map { it.trim().replace(Regex("""^([-*•]|\d+[.)])\s*"""), "").trim() }
        .filter { it.isNotBlank() && !it.startsWith("tracking", ignoreCase = true) }
        .map { it.take(200) }.distinct().take(6)
    if (task.isBlank() && criteria.isEmpty()) return null
    return BriefDraft(task, criteria)
}

internal suspend fun draftBrief(model: TextModel, request: String, context: String): BriefDraft? {
    val output = 400
    val budget = inputBudget(runCatching { model.tokenLimit() }.getOrDefault(4000), output, BriefInstructions.length + 40)
    return parseBriefDraft(model.generate(briefPrompt(request, context, budget), output))
}

// ---- Review comment rewriting ----

private const val RewriteInstructions = """Rewrite this pull request review comment so it is clear, specific and polite.
Keep its meaning and keep all code, file names, numbers and links exactly as written. Do not add new requests.
Reply with only the rewritten comment.

COMMENT:
"""

internal fun rewritePrompt(comment: String, budget: Int): String = RewriteInstructions + clip(comment.trim(), budget)

/** Strips preambles and wrapping the model sometimes adds. Returns null for empty or runaway output. */
internal fun cleanRewrite(output: String, original: String): String? {
    var text = output.trim().removePrefix("```").removeSuffix("```").trim()
    text = text.replace(Regex("""(?i)^(here('s| is) (the |a )?(rewritten|revised|clearer) (comment|version)[^:\n]*:|rewritten comment:)\s*"""), "")
    if (text.length >= 2 && text.first() == '"' && text.last() == '"') text = text.substring(1, text.length - 1)
    text = text.trim()
    if (text.isBlank() || text.length > original.length * 3 + 300) return null
    return text
}

internal suspend fun rewriteComment(model: TextModel, comment: String): String? {
    val output = (comment.length / CharsPerToken * 2 + 100).coerceIn(100, 600)
    val budget = inputBudget(runCatching { model.tokenLimit() }.getOrDefault(4000), output, RewriteInstructions.length)
    return cleanRewrite(model.generate(rewritePrompt(comment, budget), output), comment)
}

// ---- Log line suggestions ----

private const val LogInstructions = """These are numbered lines from a failed CI job log.
Reply with the numbers of the 3 to 8 lines that best show why the job failed, separated by commas. Reply with numbers only.

"""

/** Lines worth showing the model: the end of the log plus keyword hits, numbered by their real position. */
internal fun logCandidates(log: String, budget: Int): List<IndexedValue<String>> {
    val lines = log.lines().withIndex().filter { it.value.isNotBlank() }
    val keyword = Regex("(?i)error|fail|exception|panic|fatal|denied|not found|exit code")
    val picked = (lines.filter { keyword.containsMatchIn(it.value) } + lines.takeLast(40)).distinctBy { it.index }.sortedBy { it.index }
    val out = mutableListOf<IndexedValue<String>>()
    var used = 0
    // Prefer the end of the log, where failures usually are.
    for (line in picked.asReversed()) {
        val cost = line.value.length.coerceAtMost(240) + 8
        if (used + cost > budget) break
        out += IndexedValue(line.index, line.value.take(240)); used += cost
    }
    return out.sortedBy { it.index }
}

internal fun logPrompt(candidates: List<IndexedValue<String>>): String =
    LogInstructions + candidates.joinToString("\n") { "${it.index + 1}| ${it.value}" }

/** Line numbers (1-based) the model chose, limited to lines it was actually shown. */
internal fun parseLineNumbers(output: String, allowed: Set<Int>): List<Int> =
    Regex("""\d+""").findAll(output).mapNotNull { it.value.toIntOrNull() }.filter { it in allowed }.distinct().take(8).toList()

/** The chosen lines with a little surrounding context, cut from the real log. */
internal fun excerptAround(log: String, lineNumbers: List<Int>, context: Int = 2): String {
    val lines = log.lines()
    val keep = lineNumbers.flatMap { n -> ((n - 1 - context)..(n - 1 + context)) }.filter { it in lines.indices }.toSortedSet()
    val out = StringBuilder()
    var previous = -2
    for (i in keep) {
        if (previous >= 0 && i != previous + 1) out.append("…\n")
        out.append(lines[i]).append('\n')
        previous = i
    }
    return out.toString().trimEnd()
}

internal suspend fun suggestLogExcerpt(model: TextModel, log: String): String? {
    val output = 40
    val budget = inputBudget(runCatching { model.tokenLimit() }.getOrDefault(4000), output, LogInstructions.length)
    val candidates = logCandidates(log, budget)
    if (candidates.isEmpty()) return null
    val numbers = parseLineNumbers(model.generate(logPrompt(candidates), output), candidates.map { it.index + 1 }.toSet())
    return if (numbers.isEmpty()) null else excerptAround(log, numbers)
}

// ---- Release summaries ----
// The model sees only what people wrote about the changes: pull request titles and the summary part of their
// descriptions, or commit subjects. Never code or diffs. Its text goes above GitHub's factual list, which stays verbatim.

/** One change since the last release. [pull] is its pull request number when known; [details] is what its author wrote. */
internal data class ReleaseChange(val title: String, val pull: Int? = null, val details: String = "")

/** Changes listed in GitHub's generated notes ("* Title by @someone in https://…/pull/12"), without authors and links. */
internal fun releaseChanges(notes: String): List<ReleaseChange> = notes.substringBefore("## New Contributors").lines()
    .mapNotNull { Regex("""^\s*[*-]\s+(.+)$""").find(it)?.groupValues?.get(1) }
    .filterNot { it.startsWith("Full Changelog", ignoreCase = true) || it.startsWith("**Full Changelog", ignoreCase = true) }
    .mapNotNull { line ->
        val pull = Regex("""/pull/(\d+)\s*$""").find(line)?.groupValues?.get(1)?.toIntOrNull()
        val title = line.replace(Regex("""\s+by @\S+(\s+in\s+https?://\S+)?\s*$"""), "").replace(Regex("""\s+in\s+https?://\S+\s*$"""), "").trim()
        title.takeIf { it.isNotBlank() }?.let { ReleaseChange(it, pull) }
    }

internal fun changeTitles(notes: String): List<String> = releaseChanges(notes).map { it.title }

/** Commit subjects worth summarizing; merge commits only repeat a pull request's branch name. */
internal fun commitTitles(subjects: List<String>): List<String> =
    subjects.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Merge pull request") && !it.startsWith("Merge branch") }

/**
 * The part of a pull request description that says what changed, as plain text: its "Summary" section when it has one,
 * else the text before the first heading. Testing notes, tool footers, links and code blocks are left out.
 */
internal fun pullSummary(body: String, maxChars: Int = 700): String {
    var text = body.replace(Regex("""(?s)<!--.*?-->"""), "").replace(Regex("""(?s)```.*?```"""), "")
    val sections = Regex("""(?m)^#{1,6}\s+(.+)$""").findAll(text).toList()
    val summary = sections.firstOrNull { it.groupValues[1].trim().lowercase().let { h -> h.startsWith("summary") || h.startsWith("what") || h.startsWith("changes") } }
    text = when {
        summary != null -> text.substring(summary.range.last + 1).let { rest -> Regex("""(?m)^#{1,6}\s""").find(rest)?.let { rest.substring(0, it.range.first) } ?: rest }
        sections.isNotEmpty() -> text.substring(0, sections.first().range.first).ifBlank { text.substring(sections.first().range.last + 1) }
        else -> text
    }
    // In a bulleted summary the top-level bullets name the changes; nested bullets are detail that would crowd out
    // the later changes, so they're left out.
    val lines = text.lines()
    val bulleted = lines.any { Regex("""^[-*]\s""").containsMatchIn(it) }
    text = lines.filterNot { bulleted && Regex("""^\s{2,}[-*]\s""").containsMatchIn(it) }
        .filterNot { line -> line.trim().let { it == "---" || it.startsWith("🤖") || it.startsWith("_Generated by") || it.startsWith("Co-Authored-By") || it.matches(Regex("""https?://\S+""")) } }
        .joinToString("\n")
        .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        .replace(Regex("""https?://\S+"""), "")
        .replace("`", "").replace("**", "")
        .replace(TrackingIdLike, "")
        .replace(Regex("""[ \t]+"""), " ").replace(Regex("""\n\s*\n+"""), "\n").trim()
    if (text.length <= maxChars) return text
    val cut = text.take(maxChars)
    return cut.substring(0, (cut.lastIndexOf(". ").takeIf { it > maxChars / 2 } ?: cut.lastIndexOf(' ').coerceAtLeast(0)) + 1).trim()
}

/** Whether a summary can add anything: a single change with nothing written about it is already said by its title. */
internal fun worthSummarizing(changes: List<ReleaseChange>): Boolean =
    changes.size > 1 || changes.any { it.details.length >= 40 }

private const val ReleaseRules = """You write the opening of release notes for the people who use this app.
Rules:
- Use only the changes below. Say what people can now do, or what works better.
- Call something new only when its description says it was added; otherwise say what changed.
- Mention each change once. Never repeat a change in other words.
- Do not mention version numbers, pull request numbers, people, links, tests or code.
- Plain text. No headings. No "we".
"""

/** Length guidance that grows with the release, so a small release gets a sentence or two, not padding. */
internal fun releaseShape(count: Int): String = when {
    count <= 1 -> "Write one or two sentences."
    count <= 3 -> "Write one short sentence per change, at most $count sentences. No bullet points."
    else -> "Write two sentences about the release as a whole, then up to five bullet points (starting with \"- \") for the most notable changes. Bullets must not repeat the sentences."
}

internal fun releasePrompt(version: String, changes: List<ReleaseChange>, budget: Int): String {
    val head = ReleaseRules + releaseShape(changes.size) + "\nReply with only the text.\n\nCHANGES:\n"
    val out = StringBuilder()
    for ((i, change) in changes.withIndex()) {
        val title = "${i + 1}. ${change.title.take(200)}\n"
        if (head.length + out.length + title.length > budget + head.length) break
        out.append(title)
        // Details are trimmed to what fits, so many changes still all get their titles in.
        val share = ((budget - out.length) / (changes.size - i).coerceAtLeast(1)).coerceAtMost(700)
        if (change.details.isNotBlank() && share > 80) out.append("   ").append(change.details.take(share).replace("\n", " ")).append("\n")
    }
    return head + out + (if (version.isNotBlank()) "\nThe release is $version; don't mention the number.\n" else "")
}

/** Strips preambles, headings, links and repeated lines. Returns null for empty or runaway output. */
internal fun cleanReleaseSummary(output: String): String? {
    var text = output.trim().removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim()
    text = text.replace(Regex("""(?i)^(here('s| is| are)[^:\n]*:|release notes:|summary:)\s*"""), "")
    val kept = mutableListOf<String>()
    val seenWords = mutableListOf<Set<String>>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.contains("http://") || trimmed.contains("https://") || trimmed.startsWith("#")) continue
        // A line whose words were nearly all said already (a bullet restating the paragraph) is dropped.
        val words = Regex("""[a-z0-9]{3,}""").findAll(trimmed.lowercase()).map { it.value }.toSet()
        if (words.isNotEmpty() && seenWords.any { prior -> words.count { it in prior } >= words.size * 0.8 }) continue
        if (words.isNotEmpty()) seenWords += words
        kept += line.trimEnd()
    }
    text = kept.joinToString("\n").replace(TrackingIdLike, "").replace(Regex("\n{3,}"), "\n\n").trim()
    if (text.length < 20 || text.length > 2000) return null
    return text
}

internal suspend fun draftReleaseSummary(model: TextModel, version: String, changes: List<ReleaseChange>): String? {
    if (changes.isEmpty()) return null
    val output = if (changes.size <= 3) 160 else 350
    val budget = inputBudget(runCatching { model.tokenLimit() }.getOrDefault(4000), output, ReleaseRules.length + 300)
    return cleanReleaseSummary(model.generate(releasePrompt(version, changes, budget), output))
}
