package app.steady.feature.paywall

import app.steady.data.Entitlement
import app.steady.data.Features
import app.steady.data.Tier
import app.steady.feature.settings.manageSubscriptionUrl
import app.steady.remote.EntitlementLogic
import app.steady.remote.PlusProducts
import app.steady.remote.ProductOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTierTest {
    private val free = EntitlementLogic.FREE
    private val plus = Entitlement(Tier.PLUS, FreeTier.plusFeatures)

    @Test fun freeHasTwoSchedules() {
        assertEquals(2, FreeTier.scheduleLimit(free))
        assertTrue(FreeTier.canAddSchedule(free, 1))
        assertFalse(FreeTier.canAddSchedule(free, 2))
    }

    @Test fun plusIsUnlimited() {
        assertNull(FreeTier.scheduleLimit(plus))
        assertTrue(FreeTier.canAddSchedule(plus, 50))
    }

    @Test fun oneKeyHolder() {
        assertTrue(FreeTier.canAddKeyHolder(free, 0))
        assertFalse(FreeTier.canAddKeyHolder(free, 1))
        assertEquals(1, FreeTier.keyHolderLimit(plus))
    }

    @Test fun strictModeAlwaysFree() {
        assertTrue(FreeTier.strictModeAvailable(free))
        assertTrue(FreeTier.strictModeAvailable(plus))
    }

    @Test fun plusFeaturesMatchContract() {
        assertEquals(
            setOf("unlimited_schedules", "desktop_sync", "notes_to_cards", "task_breakdown"),
            FreeTier.plusFeatures,
        )
        assertEquals(2, FreeTier.SCHEDULE_LIMIT)
        assertEquals(FreeTier.plusFeatures.sorted(), FreeTier.missingPlusFeatures(free))
        assertTrue(FreeTier.missingPlusFeatures(plus).isEmpty())
        assertTrue(FreeTier.has(plus, Features.DESKTOP_SYNC))
        assertFalse(FreeTier.isPlus(free))
    }

    @Test fun pricesFromOffersOrDefaults() {
        assertEquals(null to null, pricesFrom(emptyList()))
        assertEquals(
            "₹149.00" to "₹999.00",
            pricesFrom(listOf(ProductOffer(PlusProducts.YEARLY, "₹999.00", "y"), ProductOffer(PlusProducts.MONTHLY, "₹149.00", "m"))),
        )
    }

    @Test fun manageSubscriptionDeepLink() {
        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=steady_plus_monthly&package=app.steady",
            manageSubscriptionUrl(PlusProducts.MONTHLY),
        )
        assertEquals("https://play.google.com/store/account/subscriptions", manageSubscriptionUrl(null))
    }
}
