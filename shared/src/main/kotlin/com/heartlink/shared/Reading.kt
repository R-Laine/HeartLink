package com.heartlink.shared

/** One heart-rate reading. [timestamp] is when it was measured (server time for live watch readings). */
data class Reading(val bpm: Int, val timestamp: Long, val source: String? = null)

/** Where a reading came from, stored alongside it so the UI can label it. */
object Sources {
    const val WATCH = "watch"
    const val HEALTH_CONNECT = "health"
    const val TEST = "test"

    /** Short label shown next to the reading's age, or null for live watch data. */
    fun label(source: String?): String? = when (source) {
        HEALTH_CONNECT -> "Health Connect"
        TEST -> "test beat"
        else -> null
    }
}

/** "14 min ago · Health Connect", or just "live" for watch readings. */
fun statusText(ageMs: Long, source: String?): String =
    listOfNotNull(ageText(ageMs), Sources.label(source)).joinToString(" · ")
