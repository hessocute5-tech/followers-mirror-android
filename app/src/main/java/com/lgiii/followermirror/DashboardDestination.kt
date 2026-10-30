package com.lgiii.followermirror

/** Canonical labels for the dashboard tabs. UI routing uses this enum instead of display strings. */
internal enum class DashboardDestination(val label: String) {
    NOT_FOLLOWING_BACK("NOT FOLLOWING BACK"),
    DETECTED_CHANGES("DETECTED CHANGES"),
    MUTUALS("MUTUALS"),
    FOLLOWERS("FOLLOWERS"),
    FOLLOWING("FOLLOWING"),
    HISTORY("HISTORY"),
    REVIEW_MANAGER("REVIEW MANAGER"),
    IMPORT_MANAGER("IMPORT MANAGER"),
    SETTINGS("SETTINGS"),
    ABOUT("ABOUT"),
}
