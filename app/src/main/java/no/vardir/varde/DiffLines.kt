package no.vardir.varde

/** Which version of the file a diff line belongs to, as GitHub's review comment API names them. */
internal enum class DiffSide { LEFT, RIGHT }

internal enum class DiffKind { Hunk, Added, Removed, Context, Note }

/** One line of a unified diff. [line] and [side] are set for lines a review comment can be attached to. */
internal data class DiffLine(val text: String, val kind: DiffKind, val line: Int? = null, val side: DiffSide? = null)

private val HunkHeader = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** Splits a GitHub patch into lines with their line numbers in the old (removed) or new (added and unchanged) file. */
internal fun parsePatch(patch: String): List<DiffLine> {
    var old = 0
    var new = 0
    return patch.lines().map { text ->
        val hunk = HunkHeader.find(text)
        when {
            hunk != null -> { old = hunk.groupValues[1].toInt(); new = hunk.groupValues[2].toInt(); DiffLine(text, DiffKind.Hunk) }
            text.startsWith("\\") -> DiffLine(text, DiffKind.Note)
            text.startsWith("+") -> DiffLine(text, DiffKind.Added, new++, DiffSide.RIGHT)
            text.startsWith("-") -> DiffLine(text, DiffKind.Removed, old++, DiffSide.LEFT)
            else -> { old++; DiffLine(text, DiffKind.Context, new++, DiffSide.RIGHT) }
        }
    }
}
