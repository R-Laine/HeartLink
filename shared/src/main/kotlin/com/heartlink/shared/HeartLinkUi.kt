package com.heartlink.shared

import androidx.compose.ui.graphics.Color

object HeartLinkColors {
    val Pink = Color(0xFFF5A9B8)
    val Blue = Color(0xFF5BCEFA)

    fun forPerson(id: String): Color = if (id == "a") Pink else Blue

    /** Dot and text colour for a partner status, the same on phone and watch. */
    fun forStatus(status: PartnerStatus): Color = when (status) {
        PartnerStatus.Online -> Color(0xFF4CD964)
        PartnerStatus.Inactive -> Color(0xFFFFCC00)
        PartnerStatus.Away -> Color(0xFFFF9F43)
        PartnerStatus.Asleep -> Color(0xFF8E8EF0)
        PartnerStatus.NoConnection, PartnerStatus.Unknown -> Color(0xFF9A9AA6)
    }
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
 * Scale for a heart beating "lub-dub", like the vibration: a strong swell (lub), a short gap, a
 * smaller swell (dub), then rest until the next beat. [progress] runs 0..1 over one beat of
 * [periodMs]; the double pulse takes [LUB_DUB_MS] and is squeezed to fit faster beats.
 */
fun heartbeatScale(progress: Float, periodMs: Int = 1000): Float {
    val squeeze = (periodMs / LUB_DUB_MS).coerceAtMost(1f)
    val t = progress * periodMs / squeeze // ms along the lub-dub
    return when {
        t < 70f -> 1f + t / 70f * 0.3f // lub swells
        t < 180f -> 1.3f - (t - 70f) / 110f * 0.24f // eases back
        t < 230f -> 1.06f + (t - 180f) / 50f * 0.12f // dub
        t < LUB_DUB_MS -> 1.18f - (t - 230f) / (LUB_DUB_MS - 230f) * 0.18f // settles
        else -> 1f
    }
}

/** How long the animated lub-dub takes, before resting until the next beat. */
private const val LUB_DUB_MS = 450f

/** Duration of one beat in ms for [bpm], clamped to 250–2000 ms; 1 s when unknown. */
fun beatPeriodMs(bpm: Int?): Int =
    if (bpm == null || bpm <= 0) 1000 else (60_000 / bpm).coerceIn(250, 2000)
