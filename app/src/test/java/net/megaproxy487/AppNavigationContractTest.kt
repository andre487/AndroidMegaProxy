package net.megaproxy487

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationContractTest {
    @Test
    fun everySettingsDestinationHasARegisteredRoute() {
        assertEquals(7, settingsDestinations.size)
        assertEquals(settingsDestinations.size, settingsDestinations.map { it.route }.distinct().size)
        assertTrue(settingsDestinations.all { it.route in AppRoute.registeredStaticRoutes })
    }

    @Test
    fun mainSettingsAndProfilesAreSeparateDestinations() {
        assertTrue(AppRoute.MAIN in AppRoute.registeredStaticRoutes)
        assertTrue(AppRoute.SETTINGS in AppRoute.registeredStaticRoutes)
        assertTrue(AppRoute.PROFILES in AppRoute.registeredStaticRoutes)
        assertEquals(3, setOf(AppRoute.MAIN, AppRoute.SETTINGS, AppRoute.PROFILES).size)
    }
}
