package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import java.util.UUID

internal fun validWorkLink(value: String): Boolean {
    if (value.isBlank()) return true
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.host == "github.com" &&
        Regex("""/[^/]+/[^/]+/(issues|pull)/[0-9]+/?""").matches(uri.path ?: "") &&
        uri.userInfo == null && uri.port == -1
}

@Composable
internal fun ProductWork(vm: Workspace, product: Product, repos: List<Repo>) {
    var editing by remember { mutableStateOf<WorkItem?>(null) }
    var deleting by remember { mutableStateOf<WorkItem?>(null) }
    var creatingIssue by remember { mutableStateOf<WorkItem?>(null) }
    val uriHandler = LocalUriHandler.current
    fun update(item: WorkItem) {
        val items = if (product.work.any { it.id == item.id }) product.work.map { if (it.id == item.id) item else it }
            else product.work + item
        vm.saveProduct(product.copy(work = items))
    }
    creatingIssue?.let { item ->
        ProductIssueDialog(vm, item, repos, onClose = { creatingIssue = null }, onCreated = { url ->
            vm.saveProduct(product.copy(work = product.work.map { if (it.id == item.id) it.copy(url = url, kind = "Task") else it }))
        })
    }
    editing?.let { item ->
        WorkEditor(item, onCancel = { editing = null }, onSave = { update(it); editing = null })
    }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete work item?") },
            text = { Text(item.title) },
            confirmButton = { TextButton(onClick = { vm.saveProduct(product.copy(work = product.work.filterNot { it.id == item.id })); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Work & ideas", style = MaterialTheme.typography.titleLarge)
        Text("Local items can link to a GitHub issue or pull request.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { editing = WorkItem(UUID.randomUUID().toString(), "") }, modifier = Modifier.fillMaxWidth()) {
            Text("Add work or idea")
        }
        if (product.work.isEmpty()) Text("Nothing added yet.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        product.work.forEachIndexed { index, item ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(item.done, { update(item.copy(done = it)) })
                        Column(Modifier.weight(1f).padding(top = 8.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(item.kind + if (item.done) " · Done" else "", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (item.details.isNotBlank()) Text(item.details, style = MaterialTheme.typography.bodyMedium)
                    if (item.url.isBlank() && repos.isNotEmpty()) TextButton(onClick = { creatingIssue = item }) {
                        Text("Create GitHub issue…")
                    }
                    if (item.url.isNotBlank() && validWorkLink(item.url)) TextButton(onClick = { runCatching { uriHandler.openUri(item.url.trim()) } }) { Text("Open linked GitHub item") }
                    Row {
                        TextButton(onClick = {
                            val items = product.work.toMutableList()
                            java.util.Collections.swap(items, index, index - 1)
                            vm.saveProduct(product.copy(work = items))
                        }, enabled = index > 0) { Text("↑") }
                        TextButton(onClick = {
                            val items = product.work.toMutableList()
                            java.util.Collections.swap(items, index, index + 1)
                            vm.saveProduct(product.copy(work = items))
                        }, enabled = index < product.work.lastIndex) { Text("↓") }
                        TextButton(onClick = { editing = item }) { Text("Edit") }
                        TextButton(onClick = { deleting = item }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkEditor(item: WorkItem, onCancel: () -> Unit, onSave: (WorkItem) -> Unit) {
    var title by remember { mutableStateOf(item.title) }
    var kind by remember { mutableStateOf(item.kind) }
    var details by remember { mutableStateOf(item.details) }
    var url by remember { mutableStateOf(item.url) }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(if (item.title.isBlank()) "Add work" else "Edit work") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text("$kind ▾") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf("Task", "Idea", "Milestone").forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = { kind = value; menu = false })
                        }
                    }
                }
                OutlinedTextField(details, { details = it }, label = { Text("Details") }, minLines = 2)
                OutlinedTextField(url, { url = it }, label = { Text("GitHub issue or PR URL (optional)") },
                    isError = !validWorkLink(url), supportingText = {
                        if (!validWorkLink(url)) Text("Use a GitHub issue or pull request URL.")
                    })
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && validWorkLink(url),
            onClick = { onSave(item.copy(title = title.trim(), kind = kind, details = details, url = url.trim())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
