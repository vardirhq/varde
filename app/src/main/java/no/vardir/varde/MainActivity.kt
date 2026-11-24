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
        setContent { VardeTheme { Varde() } }
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
    var attention by mutableStateOf(emptyList<AttentionItem>()); private set
    var attentionLoaded by mutableStateOf(false); private set
    var attentionError by mutableStateOf<String?>(null); private set
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
        val access = github.authorize(device,
            onConnectionProblem = { error = "Can't reach GitHub. Check your connection; retrying until the code expires." },
            onReconnected = { error = null }
        )
        store.save(access); token = access; signedIn = true
        sync(access)
    }
    private suspend fun sync(access: String) {
        user = github.login(access)
        repos = github.repositories(access)
        try {
            attention = github.assignedWork(access)
            attentionLoaded = true
            attentionError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Unauthorized) {
            throw e
        } catch (e: Exception) {
            attention = emptyList()
            attentionLoaded = false
            attentionError = "Check your connection and refresh. Assigned work may require additional GitHub permissions."
        }
    }
    fun refresh() = runTask { token?.let { sync(it) } }
    fun cancel() { job?.cancel() }
    private fun reset() { store.clear(); token = null; signedIn = false; user = ""; repos = emptyList(); attention = emptyList(); attentionLoaded = false; attentionError = null }
    fun logout() { job?.cancel(); reset(); error = null }
}
