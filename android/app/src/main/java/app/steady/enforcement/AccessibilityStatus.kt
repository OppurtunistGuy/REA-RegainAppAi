package app.steady.enforcement

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Is Steady's accessibility service switched on? Read from Settings.Secure, polled on resume. */
object AccessibilityStatus {

    fun isEnabled(context: Context): Boolean {
        val setting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return isServiceListed(setting, context.packageName, SteadyAccessibilityService::class.java.name)
    }

    /**
     * Parses ENABLED_ACCESSIBILITY_SERVICES: ':'-separated flattened component names,
     * "pkg/cls" or "pkg/.Cls" (class relative to the package).
     */
    fun isServiceListed(setting: String?, packageName: String, className: String): Boolean {
        if (setting.isNullOrBlank()) return false
        return setting.split(':').any { raw ->
            val entry = raw.trim()
            val slash = entry.indexOf('/')
            if (slash <= 0 || slash == entry.lastIndex) return@any false
            val pkg = entry.substring(0, slash)
            val cls = entry.substring(slash + 1).let { if (it.startsWith('.')) pkg + it else it }
            pkg == packageName && cls == className
        }
    }
}

/** Holds the latest [AccessibilityStatus.isEnabled]; call [refresh] on resume. */
class AccessibilityStatusTracker(context: Context, private val check: (Context) -> Boolean = AccessibilityStatus::isEnabled) {
    private val appContext = context.applicationContext
    private val state = MutableStateFlow(check(appContext))
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun refresh() {
        state.value = check(appContext)
    }
}

/** Compose helper: current accessibility state, refreshed each time the screen resumes. */
@Composable
fun rememberAccessibilityEnabled(): State<Boolean> {
    val context = LocalContext.current
    val tracker = remember(context) { AccessibilityStatusTracker(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tracker.refresh() }
    return tracker.enabled.collectAsState()
}
