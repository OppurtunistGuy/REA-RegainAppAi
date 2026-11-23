package app.steady.enforcement

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Android 13+ needs the POST_NOTIFICATIONS runtime permission for FocusService's quiet "block running"
 * notification. FocusService starts from an alarm (no UI), so the app asks once, from Today, before
 * the first block. Declining is fine: the service still runs, only the notification is hidden.
 */
object NotificationPermission {
    /** Manifest.permission.POST_NOTIFICATIONS (API 33); only used when [needed]. */
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    private const val PREFS = "steady_ui"
    private const val K_ASKED = "asked_post_notifications"

    fun needed(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

    fun granted(context: Context): Boolean =
        !needed() || ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Ask at most once per install; after that the user decides in system settings. */
    fun shouldAsk(context: Context): Boolean =
        needed() && !granted(context) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ASKED, false)

    fun markAsked(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(K_ASKED, true) }
}

/** Shows the system notification prompt once, when [enabled] (e.g. a plan with schedules exists). */
@Composable
fun AskNotificationPermissionOnce(enabled: Boolean) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(enabled) {
        if (enabled && NotificationPermission.shouldAsk(context)) {
            NotificationPermission.markAsked(context)
            launcher.launch(NotificationPermission.POST_NOTIFICATIONS)
        }
    }
}
