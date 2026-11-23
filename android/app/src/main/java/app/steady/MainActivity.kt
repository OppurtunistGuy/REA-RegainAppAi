package app.steady

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.steady.enforcement.EXTRA_ROUTE
import app.steady.enforcement.EXTRA_SCHEDULE_ID
import app.steady.nav.DeepLink
import app.steady.nav.SteadyNavHost
import app.steady.ui.theme.SteadyTheme

class MainActivity : ComponentActivity() {
    /** Deep link waiting for the NavHost (set from onCreate / onNewIntent, cleared once applied). */
    private var pendingLink by mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a configuration change the back stack is restored; don't navigate a second time.
        if (savedInstanceState == null) pendingLink = deepLinkOf(intent)
        val container = appContainer
        setContent {
            SteadyTheme {
                SteadyNavHost(
                    container = container,
                    pendingLink = pendingLink,
                    onLinkHandled = { pendingLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkOf(intent)?.let { pendingLink = it }
    }

    private fun deepLinkOf(intent: Intent?): DeepLink? {
        val route = intent?.getStringExtra(EXTRA_ROUTE) ?: return null
        return DeepLink(route, intent.getStringExtra(EXTRA_SCHEDULE_ID))
    }
}
