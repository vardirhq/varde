package no.vardir.varde

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant

/** Delegations to show in the Inbox: active ones, plus ones that finished during this session so you see them land. */
internal fun inboxDelegations(tracker: DelegationTracker): List<Pair<Delegation, DelegationStatus?>> =
    tracker.delegations
        .filter { !it.done || tracker.statuses[it.id]?.stage?.finished == true }
        .map { it to tracker.statuses[it.id] }
        .sortedWith(compareByDescending<Pair<Delegation, DelegationStatus?>> { it.second?.stage?.needsYou == true }
            .thenByDescending { it.first.createdAt })

@Composable
internal fun DelegationCard(d: Delegation, status: DelegationStatus?, now: Instant, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val stage = status?.stage
    val accent = when (stage?.tone) {
        StatusTone.Failure -> VardeTheme.colors.danger
        StatusTone.Success -> VardeTheme.colors.success
        StatusTone.Pending -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    VardeCard(modifier, onClick = onClick, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(48.dp).background(accent, RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(d.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    relativeAge(d.createdAt, now)?.let { Text(it, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(d.repository + (d.pullNumber?.let { " · #$it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (stage == null) Text("Not checked yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else StatusBadge(StatusLabel(stage.tone, stage.label))
            }
            Chevron()
        }
    }
}

/** Details and local actions for one delegation. Nothing here writes to GitHub. */
@Composable
internal fun DelegationDialog(
    d: Delegation, status: DelegationStatus?, canOpen: Boolean,
    onOpenPull: (Int) -> Unit, onLink: (Int) -> Unit, onDone: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit,
) {
    var linking by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    val parsed = number.trim().removePrefix("#").toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(onDismissRequest = onClose, title = { Text(d.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(d.repository, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status != null) {
                StatusBadge(StatusLabel(status.stage.tone, status.stage.label))
                Text(status.detail, style = MaterialTheme.typography.bodyMedium)
            } else Text("Not checked yet. Refresh the Inbox to check GitHub.", style = MaterialTheme.typography.bodyMedium)
            if (d.pullNumber == null) Text("Tracking ID: ${d.id}. If the agent's pull request doesn't include it, link the PR by number.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (linking) OutlinedTextField(number, { number = it.take(8) }, label = { Text("Pull request number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = number.isNotBlank() && parsed == null)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (!linking) {
                TextButton(onClick = { linking = true }) { Text(if (d.pullNumber == null) "Link pull request…" else "Link a different pull request…") }
                if (!d.done) TextButton(onClick = onDone) { Text("Mark done") }
                TextButton(onClick = onRemove) { Text("Stop tracking") }
            }
        }
    }, confirmButton = {
        when {
            linking -> TextButton(enabled = parsed != null, onClick = { parsed?.let(onLink) }) { Text("Link") }
            d.pullNumber != null && canOpen -> TextButton(onClick = { onOpenPull(d.pullNumber) }) { Text("Open #${d.pullNumber}") }
        }
    }, dismissButton = {
        TextButton(onClick = { if (linking) linking = false else onClose() }) { Text(if (linking) "Back" else "Close") }
    })
}
