package no.vardir.varde

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    /** Repository page requested by a notification, waiting to be opened. */
    private var link by mutableStateOf<RepositoryRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After rotation the same intent is delivered again; it was already handled.
        if (savedInstanceState == null) link = linkFrom(intent)
        setContent { VardeTheme { Varde(link = link, onLinkHandled = { link = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { link = it }
    }

    private fun linkFrom(intent: Intent?): RepositoryRoute? {
        val page = intent?.getStringExtra(VardeNotifications.ExtraPage) ?: return null
        val repoId = intent.getLongExtra(VardeNotifications.ExtraRepoId, -1)
        return if (repoId > 0 && isNotificationPage(page)) RepositoryRoute(repoId, page) else null
    }
}
