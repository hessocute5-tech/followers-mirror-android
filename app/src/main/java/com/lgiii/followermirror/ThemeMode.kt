package com.lgiii.followermirror

/** Theme preference stored independently from imported data and review preferences. */
internal enum class ThemeMode(val storedValue: String) {
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromStoredValue(value: String?): ThemeMode =
            entries.firstOrNull { it.storedValue.equals(value, ignoreCase = true) } ?: DARK
    }
}

/** Small storage adapter keeps persistence behavior testable on the JVM. */
internal class ThemeModePreference(
    private val readValue: () -> String?,
    private val writeValue: (String) -> Unit,
) {
    fun load(): ThemeMode = ThemeMode.fromStoredValue(readValue())

    fun save(mode: ThemeMode) = writeValue(mode.storedValue)
}
