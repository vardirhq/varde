package no.vardir.varde

import java.time.Duration
import java.time.Instant

/** Why something is in the inbox. Declaration order is urgency: earlier kinds win when one item has several. */
enum class AttentionKind(val label: String) {
    ChecksFailing("Checks failing"),
    WorkflowFailed("Workflow failed"),
    ReviewRequested("Review requested"),
    AssignedPullRequest("Assigned pull request"),
    AssignedIssue("Assigned issue"),
    OpenPullRequest("Open pull request"),
}

/**
 * One GitHub item that needs attention. [updatedAt] is GitHub's ISO-8601 timestamp, or blank when unknown.
 * [alsoKinds] holds the other reasons the same item showed up, after [mergeAttention].
 */
data class AttentionItem(
    val title: String,
    val repository: String,
    val kind: AttentionKind,
    val url: String,
    val repositoryId: Long,
    val updatedAt: String = "",
    val alsoKinds: Set<AttentionKind> = emptySet(),
)

internal enum class InboxSection(val title: String) { Failing("Failing"), Review("Waiting for your review"), Assigned("Assigned to you") }

internal fun AttentionItem.section(): InboxSection = when (kind) {
    AttentionKind.ChecksFailing, AttentionKind.WorkflowFailed -> InboxSection.Failing
    AttentionKind.ReviewRequested -> InboxSection.Review
    AttentionKind.AssignedPullRequest, AttentionKind.AssignedIssue, AttentionKind.OpenPullRequest -> InboxSection.Assigned
}

private fun AttentionItem.instant(): Instant? = runCatching { Instant.parse(updatedAt) }.getOrNull()

/** Collapses items that point at the same GitHub URL, keeping the most urgent kind and the newest timestamp. */
internal fun mergeAttention(items: List<AttentionItem>): List<AttentionItem> =
    items.groupBy { it.url.ifBlank { "${it.kind}:${it.title}:${it.repositoryId}" } }.values.map { group ->
        val primary = group.minBy { it.kind.ordinal }
        val newest = group.maxByOrNull { it.instant() ?: Instant.MIN }?.updatedAt.orEmpty()
        primary.copy(updatedAt = newest.ifBlank { primary.updatedAt },
            alsoKinds = (group.flatMap { it.alsoKinds + it.kind }.toSet() - primary.kind))
    }

/** Inbox sections in display order, each newest first; empty sections are omitted. */
internal fun inboxSections(items: List<AttentionItem>): List<Pair<InboxSection, List<AttentionItem>>> {
    val merged = mergeAttention(items)
    return InboxSection.entries.mapNotNull { section ->
        merged.filter { it.section() == section }
            .sortedWith(compareByDescending<AttentionItem> { it.instant() ?: Instant.MIN }.thenBy { it.title.lowercase() })
            .takeIf { it.isNotEmpty() }?.let { section to it }
    }
}

/** Compact age such as "now", "12m", "5h", "3d", "6w" or "4mo"; null when the timestamp is missing or invalid. */
internal fun relativeAge(updatedAt: String, now: Instant): String? {
    val then = runCatching { Instant.parse(updatedAt) }.getOrNull() ?: return null
    val d = Duration.between(then, now).coerceAtLeast(Duration.ZERO)
    return when {
        d.toMinutes() < 1 -> "now"
        d.toHours() < 1 -> "${d.toMinutes()}m"
        d.toDays() < 1 -> "${d.toHours()}h"
        d.toDays() < 14 -> "${d.toDays()}d"
        d.toDays() < 60 -> "${d.toDays() / 7}w"
        else -> "${d.toDays() / 30}mo"
    }
}

/** Repository page that shows [item] inside Varde, or null when it should open on GitHub. */
internal fun attentionRoute(item: AttentionItem): String? {
    val id = item.url.substringAfterLast('/')
    return when (item.kind) {
        AttentionKind.WorkflowFailed -> id.toLongOrNull()?.let { "run:$it" }
        AttentionKind.AssignedIssue -> id.toIntOrNull()?.let { "issue:$it" }
        AttentionKind.ChecksFailing, AttentionKind.ReviewRequested, AttentionKind.AssignedPullRequest,
        AttentionKind.OpenPullRequest -> id.toIntOrNull()?.let { "pull:$it" }
    }
}
