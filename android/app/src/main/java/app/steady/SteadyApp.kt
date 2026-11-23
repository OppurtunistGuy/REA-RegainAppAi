package app.steady

import android.app.Application
import android.content.Context
import android.util.Log
import app.steady.di.AppContainer
import app.steady.di.DefaultAppContainer
import app.steady.enforcement.BlockScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class SteadyApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Process-wide work that outlives screens (block alarm upkeep). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        keepBlockAlarmInSync()
    }

    /**
     * Arms the block start/end alarm (and starts / stops FocusService) on app start and again after
     * every plan save (Setup, sync from the server). Boot, update and clock / zone changes are
     * covered by `BootReceiver`; the accessibility service does the same while it is connected.
     */
    private fun keepBlockAlarmInSync() {
        appScope.launch {
            container.planRepo.plan.distinctUntilChanged().collect {
                try {
                    BlockScheduler.sync(this@SteadyApp, container)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Block sync failed", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SteadyApp"
    }
}

/** Convenience for services and activities: `context.appContainer`. */
val Context.appContainer: AppContainer
    get() = (applicationContext as SteadyApp).container
