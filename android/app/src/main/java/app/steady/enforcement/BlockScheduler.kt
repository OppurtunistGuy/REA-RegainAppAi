package app.steady.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.steady.appContainer
import app.steady.di.AppContainer
import app.steady.rules.BlockState
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Starts [FocusService] at block start and stops it at block end, using one inexact
 * `AlarmManager.setAndAllowWhileIdle` alarm for the next boundary (no SCHEDULE_EXACT_ALARM).
 * The alarm can arrive a few minutes late in Doze; [FocusService] also stops itself when the
 * block ends, and the accessibility service reads the rules live, so enforcement is not delayed.
 */
object BlockScheduler {

    /** What should be running now and when to look again. */
    data class Next(val focusRunning: Boolean, val scheduleId: String?, val wakeAt: Instant?)

    /** Pure time math: inside a block → run until its end; otherwise wake at the next start. */
    fun compute(rules: RulesEngine, plan: Plan, now: Instant): Next =
        when (val state = rules.state(plan, now)) {
            is BlockState.Active -> Next(true, state.scheduleId, atLeast(state.endsAt, now))
            BlockState.Idle -> Next(false, null, rules.nextBlock(plan, now)?.startsAt?.let { atLeast(it, now) })
        }

    /** An early or same-instant wake would loop; never schedule closer than [MIN_DELAY]. */
    private fun atLeast(at: Instant, now: Instant): Instant {
        val min = now.plus(MIN_DELAY)
        return if (at.isBefore(min)) min else at
    }

    val MIN_DELAY: Duration = Duration.ofSeconds(1)

    /** Reads the current plan and applies [compute]. Safe to call often. */
    suspend fun sync(context: Context, container: AppContainer = context.appContainer) {
        val plan = container.planRepo.plan.first()
        apply(context, compute(container.rules, plan, container.clock.instant()))
    }

    fun apply(context: Context, next: Next) {
        if (next.focusRunning) FocusService.start(context) else FocusService.stop(context)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        alarms.cancel(pi)
        next.wakeAt?.let { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.toEpochMilli(), pi) }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BlockAlarmReceiver::class.java).setAction(ACTION_BLOCK_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Runs [sync] off the main thread from a receiver, keeping the broadcast alive until done. */
    internal fun syncAsync(receiver: BroadcastReceiver, context: Context) {
        val pending = receiver.goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                sync(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Block sync failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    const val ACTION_BLOCK_ALARM = "app.steady.action.BLOCK_ALARM"
    private const val REQUEST_CODE = 6001
    private const val TAG = "SteadyScheduler"
}

/** Fires at block start/end (needs the manifest `<receiver>` entry, see enforcement README). */
class BlockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BlockScheduler.ACTION_BLOCK_ALARM) BlockScheduler.syncAsync(this, context)
    }
}

/** Re-arms the block alarm after reboot, app update and clock / time zone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> BlockScheduler.syncAsync(this, context)
        }
    }
}
