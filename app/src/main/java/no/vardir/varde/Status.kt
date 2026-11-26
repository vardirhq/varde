package no.vardir.varde

internal enum class StatusTone { Success, Failure, Pending, Neutral }

internal data class StatusLabel(val tone: StatusTone, val label: String)

/** Maps a GitHub Actions run, job or check run to a readable status. Conclusion wins once the item completed. */
internal fun ciStatus(status: String, conclusion: String): StatusLabel {
    val s = status.lowercase(java.util.Locale.ROOT)
    val c = conclusion.lowercase(java.util.Locale.ROOT).takeUnless { it == "null" }.orEmpty()
    if (s.isNotBlank() && s != "completed" && c.isBlank()) return when (s) {
        "queued", "requested", "pending" -> StatusLabel(StatusTone.Pending, "Queued")
        "waiting" -> StatusLabel(StatusTone.Pending, "Waiting")
        else -> StatusLabel(StatusTone.Pending, "In progress")
    }
    return when (c) {
        "success" -> StatusLabel(StatusTone.Success, "Passed")
        "failure" -> StatusLabel(StatusTone.Failure, "Failed")
        "timed_out" -> StatusLabel(StatusTone.Failure, "Timed out")
        "startup_failure" -> StatusLabel(StatusTone.Failure, "Startup failed")
        "action_required" -> StatusLabel(StatusTone.Failure, "Needs action")
        "cancelled" -> StatusLabel(StatusTone.Neutral, "Cancelled")
        "skipped" -> StatusLabel(StatusTone.Neutral, "Skipped")
        "stale" -> StatusLabel(StatusTone.Neutral, "Stale")
        "neutral" -> StatusLabel(StatusTone.Neutral, "Neutral")
        "" -> StatusLabel(StatusTone.Neutral, "Unknown")
        else -> StatusLabel(StatusTone.Neutral, c.replace('_', ' ').replaceFirstChar { it.uppercase() })
    }
}

/** Maps a legacy commit status state (success, failure, error, pending). */
internal fun commitStatus(state: String): StatusLabel = when (state.lowercase(java.util.Locale.ROOT)) {
    "success" -> StatusLabel(StatusTone.Success, "Passed")
    "failure" -> StatusLabel(StatusTone.Failure, "Failed")
    "error" -> StatusLabel(StatusTone.Failure, "Error")
    "pending" -> StatusLabel(StatusTone.Pending, "Pending")
    else -> StatusLabel(StatusTone.Neutral, "Unknown")
}

internal fun pullState(state: String, draft: Boolean, merged: Boolean): StatusLabel = when {
    merged -> StatusLabel(StatusTone.Neutral, "Merged")
    draft -> StatusLabel(StatusTone.Neutral, "Draft")
    state.equals("open", true) -> StatusLabel(StatusTone.Success, "Open")
    state.equals("closed", true) -> StatusLabel(StatusTone.Failure, "Closed")
    else -> StatusLabel(StatusTone.Neutral, state.ifBlank { "Unknown" }.replaceFirstChar { it.uppercase() })
}
