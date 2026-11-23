package app.steady.enforcement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityStatusTest {
    private val pkg = "app.steady"
    private val cls = "app.steady.enforcement.SteadyAccessibilityService"
    private fun listed(s: String?) = AccessibilityStatus.isServiceListed(s, pkg, cls)

    @Test fun emptyOrNull() {
        assertFalse(listed(null))
        assertFalse(listed(""))
        assertFalse(listed("   "))
    }

    @Test fun fullClassName() {
        assertTrue(listed("app.steady/app.steady.enforcement.SteadyAccessibilityService"))
    }

    @Test fun relativeClassName() {
        assertTrue(listed("app.steady/.enforcement.SteadyAccessibilityService"))
    }

    @Test fun amongOthers() {
        assertTrue(listed("com.other/.Svc:app.steady/.enforcement.SteadyAccessibilityService:com.x/com.x.Y"))
        assertTrue(listed("com.other/.Svc: app.steady/.enforcement.SteadyAccessibilityService "))
    }

    @Test fun notListedOrMalformed() {
        assertFalse(listed("com.other/.Svc"))
        assertFalse(listed("app.steady.debug/app.steady.enforcement.SteadyAccessibilityService"))
        assertFalse(listed("app.steady/.enforcement.OtherService"))
        assertFalse(listed("app.steady"))
        assertFalse(listed("/app.steady.enforcement.SteadyAccessibilityService"))
        assertFalse(listed("app.steady/"))
        assertFalse(listed("::"))
    }
}
