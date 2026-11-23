package app.steady.enforcement

import android.accessibilityservice.AccessibilityService
import app.steady.feature.intercept.InterceptUiState

/**
 * TODO(phase6): host app.steady.feature.intercept.InterceptContent in a
 * WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY window (ComposeView with its own
 * lifecycle/saved-state owners). Stub: does nothing.
 */
@Suppress("unused", "UNUSED_PARAMETER")
class InterceptOverlay(private val service: AccessibilityService) {
    var isShowing: Boolean = false
        private set

    fun show(state: InterceptUiState, onClose: () -> Unit, onSwap: () -> Unit, onMessagesOnly: () -> Unit) {
        isShowing = false
    }

    fun hide() {
        isShowing = false
    }
}
