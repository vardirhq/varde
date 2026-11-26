package no.vardir.varde

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.common.MlKit
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Gemini Nano through ML Kit's Prompt API (beta). ML Kit's start-up provider is removed in the manifest, so ML Kit is
 * initialized here only once assistance is switched on; with assistance off it never runs.
 */
internal class MlKitTextModel(private val context: Context) : TextModel {
    private val client: GenerativeModel by lazy {
        try { MlKit.initialize(context.applicationContext) } catch (_: IllegalStateException) { /* Already initialized. */ }
        Generation.getClient()
    }

    override var statusError: Throwable? = null; private set

    override suspend fun status(): ModelStatus = try {
        statusError = null
        when (client.checkStatus()) {
            FeatureStatus.AVAILABLE -> ModelStatus.Available
            FeatureStatus.DOWNLOADABLE -> ModelStatus.Downloadable
            FeatureStatus.DOWNLOADING -> ModelStatus.Downloading
            else -> ModelStatus.Unavailable
        }
    } catch (e: CancellationException) { throw e }
    catch (e: GenAiException) { statusError = AssistException(e.errorCode, e.message); ModelStatus.Unavailable }
    catch (e: Exception) { statusError = e; ModelStatus.Unavailable }

    override fun download(): Flow<DownloadState> = client.download().map { status ->
        when (status) {
            is DownloadStatus.DownloadStarted -> DownloadState.Started
            is DownloadStatus.DownloadProgress -> DownloadState.Progress(status.totalBytesDownloaded)
            is DownloadStatus.DownloadCompleted -> DownloadState.Done
            is DownloadStatus.DownloadFailed -> DownloadState.Failed(status.e.message ?: "Download failed.")
        }
    }.catch { emit(DownloadState.Failed(it.message ?: "Download failed.")) }

    override suspend fun tokenLimit(): Int = client.getTokenLimit()

    override suspend fun generate(prompt: String, maxOutputTokens: Int): String {
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = 0.2f
            this.maxOutputTokens = maxOutputTokens
        }
        return try {
            client.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
        } catch (e: GenAiException) { throw AssistException(e.errorCode, e.message) }
    }
}

/** Setting, availability and download state for on-device assistance. Off by default. */
internal class OnDeviceAssistant(context: Context, private val modelFactory: () -> TextModel) {
    private val prefs = context.getSharedPreferences("assistance", Context.MODE_PRIVATE)
    private var model: TextModel? = null

    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var status by mutableStateOf<ModelStatus?>(null); private set
    /** Readable reason when the status check itself failed. */
    var statusReason by mutableStateOf<String?>(null); private set
    var downloadedBytes by mutableStateOf<Long?>(null); private set
    var downloadError by mutableStateOf<String?>(null); private set

    /** True when features may offer assistance: switched on and the model is on the phone. */
    val ready get() = enabled && status == ModelStatus.Available

    private fun model(): TextModel = model ?: modelFactory().also { model = it }

    suspend fun setEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("enabled", value).apply()
        if (value) refresh() else status = null
    }

    suspend fun refresh() {
        if (!enabled) return
        val m = model()
        status = m.status()
        statusReason = m.statusError?.let(::assistFailure)
    }

    /** Downloads the model; the system manages the download and may wait for Wi-Fi. */
    suspend fun download() {
        if (!enabled) return
        downloadError = null
        downloadedBytes = 0
        try {
            model().download().collect { state ->
                when (state) {
                    is DownloadState.Progress -> downloadedBytes = state.bytes
                    is DownloadState.Failed -> downloadError = state.message
                    DownloadState.Done, DownloadState.Started -> Unit
                }
            }
        } finally {
            downloadedBytes = null
            refresh()
        }
    }

    /** Runs [block] with the model. A null result or an exception becomes a failure with a readable reason. */
    suspend fun <T> use(block: suspend (TextModel) -> T?): AssistResult<T> {
        if (!ready) return AssistResult.Failed("On-device AI isn't ready. Check Settings.")
        return try {
            block(model())?.let { AssistResult.Done(it) } ?: AssistResult.Failed(assistFailure(null))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { AssistResult.Failed(assistFailure(e)) }
    }

    var testResult by mutableStateOf<String?>(null); private set
    var testing by mutableStateOf(false); private set

    /** Runs a tiny prompt and reports exactly what happened, for diagnosing a phone. */
    suspend fun test() {
        testing = true; testResult = null
        refresh()
        testResult = if (status != ModelStatus.Available) "Model status: ${status?.name ?: "unknown"}. It must be available before it can run."
        else when (val result = use { it.generate("Reply with the single word: ready", 10).trim().ifBlank { null } }) {
            is AssistResult.Done -> "Worked. The model replied: \"${result.value.take(60)}\""
            is AssistResult.Failed -> result.reason
        }
        testing = false
    }
}
