package com.shivam.tally.data

import android.content.Context

/** Process-wide handles shared by the Flutter bridge and the accessibility service (same process). */
class TallyApp private constructor(context: Context) {
    val store = CountStore.get(context)
    val settings = TallySettings(context)

    companion object {
        @Volatile private var instance: TallyApp? = null
        fun get(context: Context): TallyApp =
            instance ?: synchronized(this) { instance ?: TallyApp(context.applicationContext).also { instance = it } }
    }
}
