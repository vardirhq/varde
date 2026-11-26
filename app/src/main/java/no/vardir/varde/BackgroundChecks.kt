package no.vardir.varde

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Notification settings and per-account memory of what has already been notified. */
internal class NotificationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

    var enabled by mutableStateOf(false); private set
    var categories by mutableStateOf(AlertCategory.entries.toSet()); private set
    var lastCheck by mutableStateOf(0L); private set
    var lastResult by mutableStateOf(""); private set

    init { reload() }

    /** Re-reads values the background check may have written. */
    fun reload() {
        enabled = prefs.getBoolean("enabled", false)
        categories = prefs.getStringSet("categories", null)?.mapNotNull { name -> AlertCategory.entries.firstOrNull { it.name == name } }?.toSet()
            ?: AlertCategory.entries.toSet()
        lastCheck = prefs.getLong("lastCheck", 0)
        lastResult = prefs.getString("lastResult", "").orEmpty()
    }

    fun updateEnabled(value: Boolean) { enabled = value; prefs.edit().putBoolean("enabled", value).apply() }
    fun setCategory(category: AlertCategory, on: Boolean) {
        categories = if (on) categories + category else categories - category
        prefs.edit().putStringSet("categories", categories.map { it.name }.toSet()).apply()
    }

    private fun key(user: String) = user.lowercase(Locale.ROOT)
    fun seen(user: String): Set<String> = prefs.getStringSet("${key(user)}:seen", emptySet()).orEmpty()
    fun seeded(user: String): Boolean = prefs.getBoolean("${key(user)}:seeded", false)
    fun remember(user: String, seen: Set<String>) {
        prefs.edit().putStringSet("${key(user)}:seen", seen).putBoolean("${key(user)}:seeded", true).apply()
    }
    fun recordCheck(result: String) {
        lastCheck = System.currentTimeMillis(); lastResult = result
        prefs.edit().putLong("lastCheck", lastCheck).putString("lastResult", result).apply()
    }
    /** Forgets everything account-specific, e.g. on sign-out. Settings stay. */
    fun forgetAccounts() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.endsWith(":seen") || it.endsWith(":seeded") }.forEach { editor.remove(it) }
        editor.remove("lastCheck").remove("lastResult").apply()
        lastCheck = 0; lastResult = ""
    }
}

/**
 * ETag cache for background checks. Unchanged data costs no rate limit. Entries not used during a check are dropped
 * by [prune], so the cache only ever holds what the last check needed.
 */
internal class ConditionalCache(context: Context, private val github: GitHub, user: String, private val token: String) {
    private val prefs = context.getSharedPreferences("background_cache", Context.MODE_PRIVATE)
    private val prefix = user.lowercase(Locale.ROOT) + ":"
    private val used = mutableSetOf<String>()

    suspend fun get(path: String): String {
        val key = prefix + path
        used += key
        val cached = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val response = github.getConditional(path, token, cached?.optString("etag"))
        if (response.notModified && cached != null) return cached.optString("body")
        if (response.etag.isNotBlank()) prefs.edit().putString(key, JSONObject().put("etag", response.etag).put("body", response.body).toString()).apply()
        return response.body
    }

    fun prune() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it !in used }.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        fun clear(context: Context) { context.getSharedPreferences("background_cache", Context.MODE_PRIVATE).edit().clear().apply() }
    }
}

internal object VardeNotifications {
    const val ExtraRepoId = "no.vardir.varde.REPO_ID"
    const val ExtraPage = "no.vardir.varde.PAGE"
    private const val SummaryId = 1

    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        AlertCategory.entries.forEach { category ->
            manager.createNotificationChannel(NotificationChannel(category.name, category.label, NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = category.detail })
        }
    }

    private fun openIntent(context: Context, alert: Alert?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (alert?.page != null) intent.putExtra(ExtraRepoId, alert.repoId).putExtra(ExtraPage, alert.page)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Posts each alert, or one summary when there are many. Titles hold repository names, so they are hidden on the lock screen. */
    fun post(context: Context, alerts: List<Alert>) {
        if (alerts.isEmpty() || !canPost(context)) return
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        fun base(channel: String) = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_varde_glyph)
            .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_varde_glyph).setContentTitle("Varde").setContentText("New activity").build())
        try {
            if (alerts.size > 3) {
                val style = NotificationCompat.InboxStyle()
                alerts.take(6).forEach { style.addLine(it.title) }
                if (alerts.size > 6) style.setSummaryText("+${alerts.size - 6} more")
                manager.notify(SummaryId, base(alerts.first().category.name).setContentTitle("${alerts.size} updates in Varde")
                    .setContentText(alerts.joinToString(" · ") { it.title }.take(120)).setStyle(style)
                    .setContentIntent(openIntent(context, null, SummaryId)).build())
            } else alerts.forEach { alert ->
                val id = alert.key.hashCode()
                manager.notify(id, base(alert.category.name).setContentTitle(alert.title).setContentText(alert.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
                    .setContentIntent(openIntent(context, alert, id)).build())
            }
        } catch (_: SecurityException) { /* Permission revoked between the check and posting. */ }
    }
}

internal object BackgroundChecks {
    private const val Name = "inbox-check"

    /** Schedules checks about every 30 minutes while online; keeps an existing schedule. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<InboxCheckWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(Name, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(Name) }
}

/** Read-only background check: it never changes delegations, the portfolio or repository settings. */
internal class InboxCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = NotificationPrefs(context)
        if (!settings.enabled) return Result.success()
        val token = TokenStore(context).load() ?: return Result.success()
        val repositories = RepositoryState(context) {}
        val user = repositories.lastUser()
        if (user.isBlank()) return Result.success()
        repositories.open(user, fromSnapshot = true)
        val github = GitHub(BuildConfig.GH_CLIENT_ID)
        val cache = ConditionalCache(context, github, user, token)
        try {
            val assigned = runCatching { github.assignedWork(token) }
            assigned.exceptionOrNull()?.let { if (it is Unauthorized || it is CancellationException) throw it }
            val feed = loadAttentionFeed(repositories.included, user) { cache.get(it) }
            val items = includedAttention(feed.items + assigned.getOrDefault(emptyList()), repositories.excluded)
            val tracker = DelegationTracker(context).also { it.open(user) }
            val checked = tracker.active.take(10).map { d ->
                checkDelegation(d, repositories.included.firstOrNull { it.id == d.repoId }) { path -> CachedResponse(cache.get(path), false, 0) }
            }
            val complete = !feed.incomplete && assigned.isSuccess && checked.none { it.second.stage == DelegationStage.Unknown }
            val decision = decideAlerts(attentionAlerts(items) + delegationAlerts(checked), settings.seen(user), settings.categories,
                firstRun = !settings.seeded(user), complete = complete)
            settings.remember(user, decision.seen)
            VardeNotifications.post(context, decision.post)
            cache.prune()
            settings.recordCheck(when {
                !VardeNotifications.canPost(context) -> "Checked, but Android is blocking Varde's notifications."
                complete -> "Checked GitHub."
                else -> "Checked GitHub; some data couldn't be loaded."
            })
        } catch (e: CancellationException) { throw e }
        catch (_: Unauthorized) { settings.recordCheck("GitHub session expired. Open Varde to sign in again.") }
        catch (_: Exception) { settings.recordCheck("Couldn't reach GitHub. Will try again later.") }
        return Result.success()
    }
}
