package com.lgiii.followermirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardDestinationTest {
    @Test fun notFollowingBackIsTheOnlyCanonicalLabel() {
        val labels = DashboardDestination.entries.map { it.label }
        assertTrue(labels.contains("NOT FOLLOWING BACK"))
        assertFalse(labels.contains("NOT BACK"))
    }

    @Test fun settingsAndAboutRemainAccessibleDestinations() {
        val destinations = DashboardDestination.entries.toSet()
        assertTrue(DashboardDestination.SETTINGS in destinations)
        assertTrue(DashboardDestination.ABOUT in destinations)
        assertTrue(DashboardDestination.REVIEW_MANAGER in destinations)
    }
}
