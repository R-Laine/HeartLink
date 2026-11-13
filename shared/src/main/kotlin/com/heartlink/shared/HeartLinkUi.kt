package com.heartlink.shared

import androidx.compose.ui.graphics.Color

object HeartLinkColors {
    val Pink = Color(0xFFF5A9B8)
    val Blue = Color(0xFF5BCEFA)

    fun forPerson(id: String): Color = if (id == "a") Pink else Blue
}

/** A reading younger than this is shown as "live". */
const val LIVE_THRESHOLD_MS = 20_000L

fun ageText(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 20 -> "live"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} hr ago"
    }
}

/**
 * Scale for a heart that beats once per [progress] cycle (0..1): quick 30% swell
 * over the first 15%, settle back by 40%, then rest.
 */
fun heartbeatScale(progress: Float): Float = when {
    progress < 0.15f -> 1f + (progress / 0.15f) * 0.3f
    progress < 0.4f -> 1.3f - ((progress - 0.15f) / 0.25f) * 0.3f
    else -> 1f
}

/** Duration of one beat in ms for [bpm], clamped to 250–2000 ms; 1 s when unknown. */
fun beatPeriodMs(bpm: Int?): Int =
    if (bpm == null || bpm <= 0) 1000 else (60_000 / bpm).coerceIn(250, 2000)
