package com.shivam.tally.core

/** The apps Tally counts in. Pure Kotlin: no Android classes. */
enum class TrackedApp(val pkg: String, val unit: String, val id: String) {
    INSTAGRAM("com.instagram.android", "Reels", "instagram"),
    YOUTUBE("com.google.android.youtube", "Shorts", "youtube");

    companion object {
        fun fromPackage(pkg: String?): TrackedApp? = entries.firstOrNull { it.pkg == pkg }
        fun fromId(id: String?): TrackedApp? = entries.firstOrNull { it.id == id }
    }
}
