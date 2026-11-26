package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A text field the write needs, such as a comment. A blank required field keeps the confirm button disabled. */
internal data class WriteField(val label: String, val required: Boolean = true, val initial: String = "", val minLines: Int = 3)

/**
 * Asks before a GitHub write and reports how it went. [explanation] says exactly what will change where. [perform]
 * gets the field's text and returns the message to show on success. When the connection drops mid-write the outcome
 * is unknown, and the dialog says so instead of offering a retry that could do it twice.
 */
@Composable
internal fun ConfirmedWriteDialog(
    title: String, explanation: String, confirmLabel: String, onDismiss: () -> Unit, onDone: (String) -> Unit,
    destructive: Boolean = false, field: WriteField? = null, perform: suspend (String) -> String,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(field?.initial.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var unknown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                if (field != null) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), enabled = !busy && !unknown,
                    label = { Text(field.label) }, minLines = field.minLines)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (!unknown) TextButton(enabled = !busy && (field == null || !field.required || text.isNotBlank()), onClick = {
                busy = true; error = null
                scope.launch {
                    try { onDone(perform(text.trim())) }
                    catch (e: CancellationException) { throw e }
                    catch (e: WriteOutcomeUnknown) { unknown = true; error = e.message }
                    catch (e: Exception) { error = e.message ?: "GitHub refused the request." }
                    finally { busy = false }
                }
            }, colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()) {
                Text(if (busy) "Working…" else confirmLabel)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(if (unknown) "Close and refresh" else "Cancel") } },
    )
}
