package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Button for an on-device assistance action. Only shown when assistance is on and the model is ready. */
@Composable
internal fun AssistButton(label: String, running: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled && !running) {
        if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Create, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (running) "Working on this phone…" else label)
    }
}

/** Marks text as generated and offers undo. */
@Composable
internal fun GeneratedNote(text: String, onUndo: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onUndo != null) TextButton(onClick = onUndo) { Text("Undo") }
    }
}

internal const val DraftedNote = "Drafted on this phone by on-device AI. Check it before sharing."
