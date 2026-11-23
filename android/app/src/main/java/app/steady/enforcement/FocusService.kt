package app.steady.enforcement

import android.content.Intent
import androidx.lifecycle.LifecycleService

/**
 * TODO(phase6): foreground service (type specialUse) that keeps schedule enforcement running and
 * shows the ongoing "Focus block" notification. Declared in the manifest with the
 * PROPERTY_SPECIAL_USE_FGS_SUBTYPE property. Stub: stops immediately.
 */
class FocusService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
