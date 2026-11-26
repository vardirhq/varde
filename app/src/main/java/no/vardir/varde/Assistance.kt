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
