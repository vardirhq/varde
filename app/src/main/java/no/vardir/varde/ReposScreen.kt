package no.vardir.varde

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ReposScreen(sync: SyncInfo, repositories: RepositoryState, onOpen: (Repo) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var ownersOpen by remember { mutableStateOf(false) }
    var groupingRepo by remember { mutableStateOf<Repo?>(null) }
    var groupInput by remember { mutableStateOf("") }
    val included = repositories.included
    val sorted = included.sortedWith(compareBy<Repo> { it.id !in repositories.pinned }.thenBy { repositories.groups[it.id] ?: "￿" })
    val visible = filterRepositories(sorted, query, owner, showArchived)
    groupingRepo?.let { repo ->
        AlertDialog(onDismissRequest = { groupingRepo = null }, title = { Text("Group ${repo.name}") },
            text = { OutlinedTextField(groupInput, { groupInput = it }, label = { Text("Group name") }, singleLine = true,
                supportingText = { Text("Leave empty to remove the group.") }) },
            confirmButton = { TextButton(onClick = { repositories.setGroup(repo.id, groupInput); groupingRepo = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { groupingRepo = null }) { Text("Cancel") } })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("Repositories", "${included.size} visible on GitHub") }
        item { SyncStatus(sync) }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search repositories") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, singleLine = true)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    FilterChip(selected = owner != null, onClick = { ownersOpen = true }, label = { Text(owner ?: "All owners") })
                    DropdownMenu(expanded = ownersOpen, onDismissRequest = { ownersOpen = false }) {
                        DropdownMenuItem(text = { Text("All owners") }, onClick = { owner = null; ownersOpen = false })
                        included.map { it.owner }.distinct().sorted().forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { owner = name; ownersOpen = false })
                        }
                    }
                }
                FilterChip(selected = showArchived, onClick = { showArchived = !showArchived }, label = { Text("Show archived") })
            }
        }
        if (visible.isEmpty() && !sync.busy) item {
            if (included.isEmpty()) Notice("No visible repositories", "Choose repositories in Settings or refresh GitHub.")
            else Notice("No matching repositories", "Try another search or filter.")
        }
        items(visible, key = { it.id }) { repo ->
            RepoCard(repo, pinned = repo.id in repositories.pinned, group = repositories.groups[repo.id], onClick = { onOpen(repo) },
                onTogglePin = { repositories.togglePin(repo.id) },
                onSetGroup = { groupInput = repositories.groups[repo.id].orEmpty(); groupingRepo = repo })
        }
    }
}

@Composable
private fun RepoCard(repo: Repo, pinned: Boolean, group: String?, onClick: () -> Unit, onTogglePin: () -> Unit, onSetGroup: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    VardeCard(onClick = onClick, contentPadding = PaddingValues(start = 16.dp, top = 12.dp, bottom = 14.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(repo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(repo.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options for ${repo.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin to top") }, onClick = { menu = false; onTogglePin() })
                    DropdownMenuItem(text = { Text(if (group == null) "Set group…" else "Change group…") }, onClick = { menu = false; onSetGroup() })
                }
            }
        }
        if (repo.description.isNotBlank()) Text(repo.description, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pinned) Tag("Pinned", emphasized = true)
            if (group != null) Tag(group)
            Tag(if (repo.privateRepo) "Private" else "Public")
            if (repo.archived) Tag("Archived")
        }
    }
}
