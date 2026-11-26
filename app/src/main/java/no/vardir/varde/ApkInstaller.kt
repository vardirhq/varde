package no.vardir.varde

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where an install stands after Varde hands the APK to Android. */
internal sealed interface InstallOutcome {
    data object Waiting : InstallOutcome
    data object Installed : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome
}

/** What a downloaded APK says it is, read before installing. */
internal data class ApkIdentity(val packageName: String, val versionName: String, val versionCode: Long)

/**
 * Installs APKs with Android's PackageInstaller. Android always shows its own confirmation, and refuses an update signed
 * with a different key; Varde reports that in words instead of a status code.
 */
internal object ApkInstaller {
    var outcome by mutableStateOf<InstallOutcome?>(null); internal set

    /** Whether the person has allowed Varde to install apps (Settings → Install unknown apps). */
    fun allowed(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    fun identify(context: Context, apk: File): ApkIdentity? = context.packageManager.getPackageArchiveInfo(apk.path, 0)?.let {
        ApkIdentity(it.packageName, it.versionName.orEmpty(), androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it))
    }

    /** The installed version of [packageName], if any. */
    fun installedVersion(context: Context, packageName: String): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrNull()

    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        outcome = InstallOutcome.Waiting
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("app.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val intent = Intent(context, InstallResultReceiver::class.java)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            outcome = InstallOutcome.Failed(e.message ?: "Android couldn't start the install.")
        }
    }

    internal fun failureReason(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install. Check that Varde may install apps."
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "This build is signed with a different key than the installed app, so Android can't update it. " +
            "Uninstall the installed app first (its data on this phone is removed), then install again."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This build doesn't support this phone (Android version or processor)."
        PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is damaged or isn't a valid app."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough storage to install it."
        else -> "Android couldn't install it" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }
}

/** Receives PackageInstaller results: opens Android's confirmation when needed and records the outcome. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else ApkInstaller.outcome = InstallOutcome.Failed("Android didn't show the install confirmation.")
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.outcome = InstallOutcome.Installed
            else -> ApkInstaller.outcome = InstallOutcome.Failed(
                ApkInstaller.failureReason(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }
}
