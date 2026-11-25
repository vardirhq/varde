package no.vardir.varde

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ProductEditor(original: Product, repos: List<Repo>, onCancel: () -> Unit, onSave: (Product) -> Unit) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var description by rememberSaveable(original.id) { mutableStateOf(original.description) }
    var stage by rememberSaveable(original.id) { mutableStateOf(original.stage) }
    var priority by rememberSaveable(original.id) { mutableStateOf(original.priority) }
    var next by rememberSaveable(original.id) { mutableStateOf(original.next) }
    var milestones by rememberSaveable(original.id) { mutableStateOf(original.milestones) }
    var ideas by rememberSaveable(original.id) { mutableStateOf(original.ideas) }
    var focused by rememberSaveable(original.id) { mutableStateOf(original.focused) }
    var selected by remember(original.id) { mutableStateOf(original.repositoryIds) }
    var query by rememberSaveable(original.id) { mutableStateOf("") }
    val remaining = repos.filter { it.id !in selected && (it.name.contains(query, true) || it.owner.contains(query, true)) }
        .sortedBy { it.name.lowercase() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = onCancel) { Text("‹  Cancel") }
                Text(if (original.name.isBlank()) "New product" else "Edit product", style = MaterialTheme.typography.headlineSmall)
                Text("Saved on this device for your GitHub account.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { OutlinedTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Product name") }, singleLine = true) }
            item { OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") }, minLines = 2) }
            item { ChoiceField("Stage", stage, listOf("Exploring", "Building", "Maintaining", "Paused")) { stage = it } }
            item { ChoiceField("Priority", priority, listOf("High", "Normal", "Low")) { priority = it } }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(focused, { focused = it }, modifier = Modifier.semantics { contentDescription = "Focus this product on Home" })
                Text("Focus on Home", Modifier.padding(start = 12.dp).clickable { focused = !focused })
            } }
            item { OutlinedTextField(next, { next = it }, Modifier.fillMaxWidth(), label = { Text("What's next?") }, minLines = 2) }
            item { OutlinedTextField(milestones, { milestones = it }, Modifier.fillMaxWidth(), label = { Text("Milestones") },
                supportingText = { Text("One per line, if you like") }, minLines = 2) }
            item { OutlinedTextField(ideas, { ideas = it }, Modifier.fillMaxWidth(), label = { Text("Ideas") }, minLines = 2) }
            item { Text("Linked repositories", style = MaterialTheme.typography.titleLarge) }
            if (selected.isEmpty()) item { Text("Choose repositories below.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(selected, key = { it }) { id ->
                val index = selected.indexOf(id)
                val repo = repos.firstOrNull { it.id == id }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(repo?.name ?: "Hidden or unavailable repository")
                        if (repo != null) Text(repo.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { selected = moveRepository(selected, index, -1) }, enabled = index > 0,
                        modifier = Modifier.semantics { contentDescription = "Move ${repo?.name ?: "repository"} up" }) { Text("↑") }
                    TextButton(onClick = { selected = moveRepository(selected, index, 1) }, enabled = index < selected.lastIndex,
                        modifier = Modifier.semantics { contentDescription = "Move ${repo?.name ?: "repository"} down" }) { Text("↓") }
                    TextButton(onClick = { selected = selected - id },
                        modifier = Modifier.semantics { contentDescription = "Unlink ${repo?.name ?: "repository"}" }) { Text("Remove") }
                }
            }
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Find repository to add") }, singleLine = true) }
            items(remaining, key = { it.id }) { repo ->
                Row(Modifier.fillMaxWidth().clickable { selected = selected + repo.id }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(repo.name); Text(repo.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { selected = selected + repo.id }) { Text("Add") }
                }
            }
            item {
                Button(onClick = { onSave(original.copy(name = name, description = description, stage = stage,
                    priority = priority, next = next, milestones = milestones, ideas = ideas,
                    repositoryIds = selected, focused = focused)) },
                    enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save product") }
            }
        }
    }
}

@Composable
private fun ChoiceField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $value  ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}
