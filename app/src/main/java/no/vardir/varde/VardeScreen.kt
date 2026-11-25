package no.vardir.varde

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Calendar

private val Amber = Color(0xFFF2A43B)
private val Ink = Color(0xFF101720)
private val Panel = Color(0xFF1D252F)
private val Muted = Color(0xFFB8B6B5)
private val Line = Color(0xFF39424D)

private val VardeColors = darkColorScheme(primary = Amber, onPrimary = Color(0xFF241707), background = Ink,
    surface = Ink, surfaceVariant = Panel, onSurface = Color(0xFFF5EEE8), onSurfaceVariant = Muted,
    outline = Line, error = Color(0xFFFF807E))

@Composable
fun VardeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VardeColors, content = content)
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home), Projects("Projects", Icons.Default.List),
    Inbox("Inbox", Icons.Default.Notifications), Settings("Settings", Icons.Default.Settings)
}

@Composable
fun Varde(vm: Workspace = viewModel()) {
    var selectedRepo by remember { mutableStateOf<Repo?>(null) }
    var selectedPage by remember { mutableStateOf("overview") }
    selectedRepo?.let { repo ->
        RepositoryWorkspace(vm, repo, selectedPage) { selectedRepo = null; selectedPage = "overview" }
        return
    }
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var privateAccess by rememberSaveable { mutableStateOf(false) }
    var publicWrite by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var ownersOpen by remember { mutableStateOf(false) }
    var exclusionQuery by rememberSaveable { mutableStateOf("") }
    val includedRepos = includedRepositories(vm.repos, vm.excludedRepos)
    var groupingRepo by remember { mutableStateOf<Repo?>(null) }
    var groupInput by remember { mutableStateOf("") }
    val sortedRepos = includedRepos.sortedWith(compareBy<Repo> { it.id !in vm.pinnedRepos }.thenBy { vm.repoGroups[it.id] ?: "\uFFFF" })
    val includedWork = includedAttention(vm.feedItems + vm.attention, vm.excludedRepos)
    val visible = filterRepositories(sortedRepos, query, owner, showArchived)
    val manageableRepos = vm.repos.filter {
        it.name.contains(exclusionQuery, true) || it.owner.contains(exclusionQuery, true)
    }
    groupingRepo?.let { repo ->
        AlertDialog(onDismissRequest = { groupingRepo = null }, title = { Text("Group ${repo.name}") },
            text = { OutlinedTextField(groupInput, { groupInput = it }, label = { Text("Group name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.setGroup(repo.id, groupInput); groupingRepo = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { groupingRepo = null }) { Text("Cancel") } })
    }
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            if (vm.signedIn) NavigationBar(containerColor = Color(0xFF151D26), contentColor = Color.White) {
                Tab.entries.forEach { destination ->
                    NavigationBarItem(selected = tab == destination, onClick = { tab = destination },
                        icon = { Icon(destination.icon, contentDescription = null) }, label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Amber, selectedTextColor = Amber,
                            indicatorColor = Color(0xFF3B3021), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { VardeHeader() }
            vm.snapshotTime?.let { time -> item { Text("Saved projects · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))}", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary) } }
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
            vm.error?.let { message -> item { Text(message, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) } }
            if (!vm.signedIn) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Your development workspace", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Manage your GitHub projects from anywhere.", color = Muted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(privateAccess, { privateAccess = it }, enabled = !vm.busy)
                            Text("Include private repositories (grants broad repo access)", Modifier.clickable(enabled = !vm.busy) { privateAccess = !privateAccess })
                        }
                        if (!privateAccess) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(publicWrite, { publicWrite = it }, enabled = !vm.busy)
                            Text("Allow writing to public repositories (public_repo scope)",
                                Modifier.clickable(enabled = !vm.busy) { publicWrite = !publicWrite })
                        }
                        Button(onClick = { vm.signIn(privateAccess, publicWrite) }, enabled = !vm.busy) { Text("Sign in with GitHub") }
                        vm.code?.let { code ->
                            Text("Enter this code on GitHub", style = MaterialTheme.typography.titleMedium)
                            Text(code, style = MaterialTheme.typography.headlineMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy code") }
                                Button(onClick = { uri.openUri("https://github.com/login/device") }) { Text("Open GitHub") }
                            }
                        }
                        if (vm.busy) TextButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
            } else when (tab) {
                Tab.Home -> {
                    item { Hero(vm.user) }
                    item { SectionHeading("Needs attention", "See all") { tab = Tab.Inbox } }
                    if (vm.feedLoading) item { Text("Checking repositories for attention…", Modifier.padding(horizontal = 20.dp), color = Muted, style = MaterialTheme.typography.bodySmall) }
                    if (vm.feedIncomplete) item { Text("Some attention data may be missing due to permissions, rate limits, or result limits.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (vm.attentionError != null) item { InfoPanel("Couldn't load assigned work", vm.attentionError!!) }
                    else if (!vm.attentionLoaded && vm.busy) item { InfoPanel("Loading assigned work", "Checking GitHub for open issues and pull requests assigned to you.") }
                    else if (!vm.attentionLoaded) item { InfoPanel("Assigned work not loaded", "Refresh to check GitHub for assigned work.") }
                    else if (includedWork.isEmpty() && !vm.feedLoading && !vm.feedIncomplete) item { InfoPanel("No assigned work", "Review requests, failed checks, workflows, and assigned work will appear here.") }
                    items(includedWork.take(3), key = { "${it.kind}:${it.url}" }) { entry -> AttentionCard(entry) {
                        val target = includedRepos.firstOrNull { it.id == entry.repositoryId }
                        val id = entry.url.substringAfterLast('/')
                        val route = when {
                            entry.kind == "Latest workflow run failed" && id.toLongOrNull() != null -> "run:$id"
                            entry.kind == "Issue" && id.toIntOrNull() != null -> "issue:$id"
                            (entry.kind == "PR checks failing" || entry.kind == "Review requested" || entry.kind == "Pull request") && id.toIntOrNull() != null -> "pull:$id"
                            else -> null
                        }
                        if (target != null && route != null) { selectedPage = route; selectedRepo = target }
                        else uri.openUri(entry.url)
                    } }
                    item { SectionHeading("Your projects", "See all") { tab = Tab.Projects } }
                    if (includedRepos.isEmpty() && !vm.busy) item { InfoPanel("No visible repositories", "Choose repositories in Settings or refresh GitHub.") }
                    items(dashboardRepositories(sortedRepos), key = { it.id }) { repo -> RepoCard(repo) { selectedPage = "overview"; selectedRepo = repo } }
                }
                Tab.Projects -> {
                    item { PageTitle("Your projects", "Repositories accessible on GitHub") }
                    item { OutlinedTextField(query, { query = it }, label = { Text("Search repositories") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
                    item { Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box { OutlinedButton(onClick = { ownersOpen = true }) { Text(owner ?: "All owners") }
                            DropdownMenu(expanded = ownersOpen, onDismissRequest = { ownersOpen = false }) {
                                DropdownMenuItem(text = { Text("All owners") }, onClick = { owner = null; ownersOpen = false })
                                includedRepos.map { it.owner }.distinct().sorted().forEach { name ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = { owner = name; ownersOpen = false })
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Checkbox(showArchived, { showArchived = it })
                        Text("Archived", Modifier.clickable { showArchived = !showArchived })
                    } }
                    if (visible.isEmpty() && !vm.busy) item { InfoPanel("No matching repositories", "Try another search or filter.") }
                    items(visible, key = { it.id }) { repo ->
                        Column {
                            RepoCard(repo) { selectedPage = "overview"; selectedRepo = repo }
                            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { vm.togglePin(repo.id) }) { Text(if (repo.id in vm.pinnedRepos) "Unpin" else "Pin") }
                                TextButton(onClick = { groupInput = vm.repoGroups[repo.id].orEmpty(); groupingRepo = repo }) {
                                    Text(vm.repoGroups[repo.id]?.let { "Group: $it" } ?: "Set group")
                                }
                            }
                        }
                    }
                }
                Tab.Inbox -> {
                    item { PageTitle("Inbox", "Open issues and pull requests assigned to you") }
                    item { Text("GitHub data is refreshed when the app refreshes.", Modifier.padding(horizontal = 20.dp), color = Muted, style = MaterialTheme.typography.bodySmall) }
                    if (vm.feedLoading) item { Text("Checking repositories for attention…", Modifier.padding(horizontal = 20.dp), color = Muted, style = MaterialTheme.typography.bodySmall) }
                    if (vm.feedIncomplete) item { Text("Some attention data may be missing due to permissions, rate limits, or result limits.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (vm.attentionError != null) item { InfoPanel("Couldn't load assigned work", vm.attentionError!!) }
                    else if (!vm.attentionLoaded && vm.busy) item { InfoPanel("Loading", "Checking GitHub for assigned work.") }
                    else if (!vm.attentionLoaded) item { InfoPanel("Assigned work not loaded", "Refresh to check GitHub for assigned work.") }
                    else if (includedWork.isEmpty() && !vm.feedLoading && !vm.feedIncomplete) item { InfoPanel("Nothing assigned", "Your assigned issues and pull requests will appear here.") }
                    items(includedWork, key = { "${it.kind}:${it.url}" }) { entry -> AttentionCard(entry) {
                        val target = includedRepos.firstOrNull { it.id == entry.repositoryId }
                        val id = entry.url.substringAfterLast('/')
                        val route = when {
                            entry.kind == "Latest workflow run failed" && id.toLongOrNull() != null -> "run:$id"
                            (entry.kind == "PR checks failing" || entry.kind == "Review requested" || entry.kind == "Pull request") && id.toIntOrNull() != null -> "pull:$id"
                            else -> null
                        }
                        if (target != null && route != null) { selectedPage = route; selectedRepo = target }
                        else uri.openUri(entry.url)
                    } }
                }
                Tab.Settings -> {
                    item { PageTitle("Settings", "Your GitHub connection") }
                    item { InfoPanel("Signed in as ${vm.user.ifBlank { "GitHub user" }}", "${includedRepos.size} visible · ${vm.excludedRepos.size} excluded") }
                    item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::refresh, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Refresh GitHub data") }
                        OutlinedButton(onClick = { vm.logout(); tab = Tab.Home; owner = null; query = "" }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                    } }
                    item { SectionHeading("Repositories", "Clear search") { exclusionQuery = "" } }
                    item { Text("Excluded repositories and their assigned work are hidden in Varde. GitHub is unchanged.", Modifier.padding(horizontal = 20.dp), color = Muted, style = MaterialTheme.typography.bodyMedium) }
                    item { OutlinedTextField(exclusionQuery, { exclusionQuery = it }, label = { Text("Find a repository") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
                    if (manageableRepos.isEmpty()) item { InfoPanel("No matching repositories", "Try another search or refresh GitHub.") }
                    items(manageableRepos, key = { it.id }) { repo ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(repo.name, style = MaterialTheme.typography.titleMedium)
                                Text(repo.owner, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            val included = repo.id !in vm.excludedRepos
                            Text(if (included) "Included" else "Excluded", color = Muted, style = MaterialTheme.typography.labelMedium)
                            Switch(checked = included, onCheckedChange = { vm.setExcluded(repo.id, !it) }, modifier = Modifier.semantics { contentDescription = "Include ${repo.owner}/${repo.name}" })
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun VardeHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(R.drawable.ic_varde_mark), contentDescription = null,
            tint = Amber, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Text("Varde", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun Hero(user: String) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
    val today = remember { Calendar.getInstance() }
    val seed = today.get(Calendar.YEAR).toLong() * 1000 + today.get(Calendar.DAY_OF_YEAR)
    Box(Modifier.fillMaxWidth().heightIn(min = 145.dp)) {
        LandscapeBanner(seed = seed, night = hour < 7 || hour >= 19, modifier = Modifier.matchParentSize())
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            Text(if (user.isBlank()) greeting else "$greeting, $user", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Your development workspace", color = Muted, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable private fun SectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable private fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted)
    }
}

@Composable private fun InfoPanel(title: String, message: String) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.outlinedCardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun AttentionCard(entry: AttentionItem, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.outlinedCardColors(containerColor = Panel)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(44.dp).background(Amber, RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("${entry.repository} · ${entry.kind}", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = Muted)
        }
    }
}

@Composable private fun RepoCard(repo: Repo, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.outlinedCardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(repo.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (repo.privateRepo) "Private" else "Public", color = Amber, style = MaterialTheme.typography.labelMedium)
            }
            Text(repo.owner + if (repo.archived) " · Archived" else "", color = Muted, style = MaterialTheme.typography.labelLarge)
            if (repo.description.isNotBlank()) Text(repo.description, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            Text("Open workspace  ›", color = Amber, style = MaterialTheme.typography.labelLarge)
        }
    }
}
