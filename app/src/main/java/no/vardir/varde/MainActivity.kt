package no.vardir.varde

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Varde() } }
    }
}
class Workspace(application: Application) : AndroidViewModel(application) {
    private val store = TokenStore(application)
    private val github = GitHub(BuildConfig.GH_CLIENT_ID)
    private var token = store.load()
    private var job: Job? = null
    var signedIn by mutableStateOf(token != null); private set
    var user by mutableStateOf(""); private set
    var repos by mutableStateOf(emptyList<Repo>()); private set
    var code by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    init { if (signedIn) refresh() }
    private fun runTask(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Unauthorized) { reset(); error = "Session expired. Sign in again." }
            catch (e: Exception) { error = e.message ?: "Request failed." }
            finally { busy = false; code = null }
        }
    }
    fun signIn(privateAccess: Boolean) = runTask {
        val device = github.start(privateAccess)
        code = device.userCode
        val access = github.authorize(device)
        store.save(access); token = access; signedIn = true
        sync(access)
    }
    private suspend fun sync(access: String) {
        user = github.login(access)
        repos = github.repositories(access)
    }
    fun refresh() = runTask { token?.let { sync(it) } }
    fun cancel() { job?.cancel() }
    private fun reset() { store.clear(); token = null; signedIn = false; user = ""; repos = emptyList() }
    fun logout() { job?.cancel(); reset(); error = null }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Varde(vm: Workspace = viewModel()) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var privateAccess by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf<String?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    var ownersOpen by remember { mutableStateOf(false) }
    val visible = vm.repos.filter { (owner == null || it.owner == owner) && (showArchived || !it.archived) && (it.name.contains(query,true) || it.owner.contains(query,true)) }
    Scaffold(topBar = { TopAppBar(title = { Text("Varde") }, actions = {
        if (vm.signedIn) { TextButton(onClick = vm::refresh, enabled = !vm.busy) { Text("Refresh") }; TextButton(onClick = { vm.logout(); owner = null; query = "" }) { Text("Sign out") } }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            vm.error?.let { message -> item { Text(message,color = MaterialTheme.colorScheme.error) } }
            if (!vm.signedIn) {
                item { Text("Your development workspace",style = MaterialTheme.typography.headlineLarge) }
                item { Text("Manage your GitHub portfolio from anywhere.") }
                item { Row { Checkbox(privateAccess,{ privateAccess = it },enabled = !vm.busy); Text("Include private repositories (grants broad repo access)",Modifier.padding(top = 12.dp)) } }
                item { Button(onClick = { vm.signIn(privateAccess) },enabled = !vm.busy) { Text("Sign in with GitHub") } }
                vm.code?.let { code -> item {
                    Text("Enter this code on GitHub",style = MaterialTheme.typography.titleMedium)
                    Text(code,style = MaterialTheme.typography.headlineLarge)
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy code") }
                    Button(onClick = { uri.openUri("https://github.com/login/device") }) { Text("Open GitHub") }
                } }
                if (vm.busy) item { TextButton(onClick = vm::cancel) { Text("Cancel") } }
            } else {
                item { Text("Your workspace",style = MaterialTheme.typography.headlineLarge); Text(vm.user) }
                item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("${vm.repos.size}",style = MaterialTheme.typography.displaySmall); Text("Accessible repositories") } } }
                item { OutlinedTextField(query,{ query = it },label = { Text("Search repositories") },singleLine = true,modifier = Modifier.fillMaxWidth()) }
                item { Row { Box { OutlinedButton(onClick = { ownersOpen = true }) { Text(owner ?: "All owners") }; DropdownMenu(ownersOpen,{ ownersOpen = false }) {
                    DropdownMenuItem(text = { Text("All owners") },onClick = { owner = null; ownersOpen = false })
                    vm.repos.map { it.owner }.distinct().sorted().forEach { name -> DropdownMenuItem(text = { Text(name) },onClick = { owner = name; ownersOpen = false }) }
                } }; Checkbox(showArchived,{ showArchived = it }); Text("Archived",Modifier.padding(top = 12.dp)) } }
                if (visible.isEmpty() && !vm.busy) item { Text("No matching repositories.") }
                items(visible,key = { it.id }) { repo ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(repo.name,style = MaterialTheme.typography.titleLarge)
                        Text(repo.owner + if (repo.privateRepo) " · Private" else " · Public")
                        if (repo.archived) Text("Archived")
                        if (repo.description.isNotBlank()) Text(repo.description)
                        TextButton(onClick = { uri.openUri(repo.url) }) { Text("Open on GitHub") }
                    } }
                }
            }
        }
    }
}
