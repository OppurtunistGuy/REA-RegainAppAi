package app.steady.enforcement

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * TODO(phase6): map AccessibilityNodeInfo to app.steady.detect.ViewNode, run SurfaceDetector,
 * apply FallbackLadder and show InterceptOverlay. Never store or send screen contents.
 * Declared in the manifest with res/xml/accessibility_service_config.xml.
 */
class SteadyAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
