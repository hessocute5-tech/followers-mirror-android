package com.lgiii.followermirror

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test fun missingOrUnknownPreferenceDefaultsToDark() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStoredValue(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStoredValue("unknown"))
    }

    @Test fun lightThemeSelectionPersistsAndRestores() {
        val storage = mutableMapOf<String, String>()
        val firstStore = ThemeModePreference(
            readValue = { storage["theme_mode"] },
            writeValue = { storage["theme_mode"] = it },
        )

        firstStore.save(ThemeMode.LIGHT)

        val restoredStore = ThemeModePreference(
            readValue = { storage["theme_mode"] },
            writeValue = { storage["theme_mode"] = it },
        )
        assertEquals(ThemeMode.LIGHT, restoredStore.load())
    }

    @Test fun switchingBackToDarkAlsoPersists() {
        var savedValue: String? = null
        val store = ThemeModePreference({ savedValue }, { savedValue = it })

        store.save(ThemeMode.LIGHT)
        store.save(ThemeMode.DARK)

        assertEquals("dark", savedValue)
        assertEquals(ThemeMode.DARK, store.load())
    }

    @Test fun savingThemeLeavesUnrelatedPreferencesUntouched() {
        val storage = mutableMapOf(
            "imported_datasets_v1" to "saved-datasets",
            "snapshots" to "saved-history",
        )
        val store = ThemeModePreference(
            readValue = { storage["theme_mode"] },
            writeValue = { storage["theme_mode"] = it },
        )

        store.save(ThemeMode.LIGHT)

        assertEquals("saved-datasets", storage["imported_datasets_v1"])
        assertEquals("saved-history", storage["snapshots"])
        assertEquals("light", storage["theme_mode"])
    }
}
