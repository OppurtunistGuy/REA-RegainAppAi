package app.steady.enforcement

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.steady.feature.intercept.InterceptContent
import app.steady.feature.intercept.InterceptUiState
import app.steady.ui.theme.SteadyTheme

/**
 * Hosts [InterceptContent] in a full-screen TYPE_ACCESSIBILITY_OVERLAY window. Only an enabled
 * accessibility service can add this window type, so no SYSTEM_ALERT_WINDOW permission is needed.
 * Call on the main thread.
 */
class InterceptOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    val isShowing: Boolean get() = view != null

    fun show(state: InterceptUiState, onClose: () -> Unit, onSwap: () -> Unit, onMessagesOnly: () -> Unit) {
        hide()
        val owner = OverlayOwner().also { it.start() }
        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { OverlayContent(state, onClose, onSwap, onMessagesOnly) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        try {
            windowManager.addView(composeView, params)
            view = composeView
            this.owner = owner
        } catch (e: RuntimeException) {
            // WindowManager.BadTokenException (service not connected) or IllegalStateException.
            Log.w(TAG, "Could not show intercept overlay", e)
            owner.destroy()
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        runCatching { windowManager.removeViewImmediate(v) }
        owner?.destroy()
        owner = null
    }

    private companion object {
        const val TAG = "SteadyOverlay"
    }
}

@Composable
private fun OverlayContent(
    state: InterceptUiState,
    onClose: () -> Unit,
    onSwap: () -> Unit,
    onMessagesOnly: () -> Unit,
) {
    SteadyTheme {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().safeDrawingPadding()) {
                InterceptContent(state = state, onClose = onClose, onSwap = onSwap, onMessagesOnly = onMessagesOnly)
            }
        }
    }
}

/** Lifecycle + saved state + view-model store for a ComposeView that lives outside an Activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) {
            store.clear()
            return
        }
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
