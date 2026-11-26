package no.vardir.varde

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun PortfolioBackupControls(portfolio: PortfolioStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportData by remember { mutableStateOf<String?>(null) }
    var candidate by remember { mutableStateOf<String?>(null) }
    var candidateAccount by remember { mutableStateOf("") }
    var candidateCount by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = exportData
        if (uri != null && text != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("Could not open the selected file.")
                }
                message = "Portfolio exported."
            } catch (_: Exception) { message = "Could not export the portfolio." }
            finally { busy = false; exportData = null }
        } else exportData = null
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= 2_000_000) { "Backup is too large." }
                            out.write(buffer, 0, count)
                        }
                        out.toString("UTF-8")
                    } ?: error("Could not open the selected file.")
                }
                val account = portfolio.account
                val parsed = decodePortfolioBackup(raw, account)
                candidateCount = parsed.size
                candidateAccount = account
                candidate = raw
                message = null
            } catch (e: Exception) { message = e.message ?: "Could not read the backup." }
            finally { busy = false }
        }
    }
    candidate?.let { raw ->
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text("Replace this portfolio?") },
            text = { Text("Restore $candidateCount products for $candidateAccount? This replaces the current local products and their notes. GitHub is unchanged.") },
            confirmButton = { TextButton(onClick = {
                try { portfolio.restore(raw, candidateAccount); message = "Portfolio restored." }
                catch (e: Exception) { message = e.message ?: "Could not restore the portfolio." }
                candidate = null
            }) { Text("Replace portfolio") } },
            dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel") } })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Portfolio backup", style = MaterialTheme.typography.titleMedium)
        Text("Export includes product notes and repository IDs. Keep the file somewhere you trust.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && portfolio.error == null && portfolio.account.isNotBlank(), onClick = {
                try { exportData = portfolio.backup(); export.launch("varde-portfolio.json") }
                catch (e: Exception) { message = e.message ?: "Could not prepare the backup." }
            }) { Text("Export") }
            OutlinedButton(enabled = !busy && portfolio.account.isNotBlank(), onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text("Restore")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
