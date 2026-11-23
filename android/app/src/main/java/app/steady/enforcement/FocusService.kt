package app.steady.enforcement

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.steady.R
import app.steady.appContainer
import app.steady.rules.BlockState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Foreground service (type specialUse) that runs for the length of a scheduled block and shows a
 * quiet ongoing notification ("Coaching block · 1:12 left"). It reads the rules itself every tick
 * and stops as soon as no block is active, so a late end alarm never leaves it running.
 */
class FocusService : LifecycleService() {
    private var ticker: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        // Must be in the foreground within a few seconds of startForegroundService.
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(getString(R.string.enforcement_notification_fallback_label), null, null), fgsType())
        }
        if (started.isFailure) {
            Log.w(TAG, "startForeground refused", started.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }
        if (ticker?.isActive != true) ticker = lifecycleScope.launch { tick() }
        return START_NOT_STICKY
    }

    private suspend fun tick() {
        val container = appContainer
        val nm = getSystemService(NotificationManager::class.java)
        while (true) {
            val plan = container.planRepo.plan.first()
            val now = container.clock.instant()
            val state = container.rules.state(plan, now)
            if (state !is BlockState.Active) {
                stopSelf()
                return
            }
            val left = Duration.between(now, state.endsAt)
            val label = scheduleLabel(plan, state.scheduleId, getString(R.string.enforcement_notification_fallback_label))
            nm?.notify(NOTIFICATION_ID, notification(label, FocusTime.formatTimeLeft(left), state.scheduleId))
            delay(FocusTime.nextTickMillis(left))
        }
    }

    private fun notification(label: String, timeLeft: String?, scheduleId: String?): Notification {
        val text = if (timeLeft == null) label else getString(R.string.enforcement_notification_text, label, timeLeft)
        val open = PendingIntent.getActivity(
            this, 0, launchIntent(this, ROUTE_FOCUS, scheduleId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.enforcement_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    private fun fgsType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    companion object {
        const val ACTION_START = "app.steady.action.FOCUS_START"
        const val ACTION_STOP = "app.steady.action.FOCUS_STOP"
        const val CHANNEL_ID = "focus_block"
        const val NOTIFICATION_ID = 6100
        private const val TAG = "SteadyFocusService"

        /**
         * Android 12+ can refuse a foreground-service start from the background (e.g. from an inexact
         * alarm while the accessibility service is off). That is logged, not fatal: enforcement in
         * the accessibility service does not depend on this service.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FocusService::class.java).setAction(ACTION_START))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not start focus service", e)
            }
        }

        // Lint false positive: stopService matches by component, a fresh Intent is correct.
        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            context.stopService(Intent(context, FocusService::class.java))
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.enforcement_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = context.getString(R.string.enforcement_channel_description)
                        setShowBadge(false)
                    },
            )
        }
    }
}

/** Pure notification time math (kept outside the Service class so JVM tests can load it). */
object FocusTime {
    /** "1:12" for an hour or more, "45 min" below; minutes round up so the last seconds read "1 min". */
    fun formatTimeLeft(left: Duration): String {
        val totalMin = ((left.toMillis().coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        return if (totalMin >= 60) "${totalMin / 60}:${(totalMin % 60).toString().padStart(2, '0')}" else "$totalMin min"
    }

    /** Re-render when the displayed minute changes, at most every 60 s, at least 1 s. */
    fun nextTickMillis(left: Duration): Long {
        val ms = left.toMillis()
        if (ms <= 0) return 1_000
        val toMinute = ms % 60_000
        return (if (toMinute == 0L) 60_000 else toMinute).coerceIn(1_000, 60_000)
    }
}
