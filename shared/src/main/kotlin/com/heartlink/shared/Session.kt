package com.heartlink.shared

import java.security.SecureRandom

/** The two seats in a couple. Whoever creates the link is [A]; their partner is [B]. */
object Roles {
    const val A = "a"
    const val B = "b"
    val all = listOf(A, B)

    fun partnerOf(role: String): String = if (role == A) B else A
}

/**
 * This device belongs to [role] in the couple [coupleId]. The couple ID is a long random key
 * that is never shown to users; access is granted per device, not by knowing a code.
 */
data class Session(val coupleId: String, val role: String) {
    val partnerRole: String get() = Roles.partnerOf(role)
}

/** A one-time invite: lets one device join [coupleId] as [role], then disappears. */
data class Invite(
    val code: String,
    val coupleId: String,
    val role: String,
    /** Name of whoever created the invite, e.g. "Sam invited you". */
    val fromName: String?,
    /** Name already on the seat (adding another device for an existing person), or null for a new partner. */
    val seatName: String?,
    val expiresAt: Long,
)

/** A partner invite that hasn't been used yet. */
data class PendingInvite(val code: String, val expiresAt: Long) {
    fun isExpired(now: Long): Boolean = expiresAt <= now
}

/** Short, human-friendly one-time codes. */
object InviteCode {
    const val LENGTH = 6

    // No 0/O, 1/I/L, so codes are easy to read out and type.
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun generate(): String = buildString {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** Uppercases and drops spaces/dashes, so "k7q m2x" and "K7Q-M2X" both work. */
    fun normalize(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}

object Names {
    const val MAX_LENGTH = 30

    fun clean(input: String): String = input.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
}

/** Wear OS Data Layer message paths used to pair a watch through its phone. */
object WearPaths {
    const val PREFIX = "/heartlink"

    /** Watch → phone. Payload: the watch's Firebase user ID. */
    const val PAIR_REQUEST = "$PREFIX/pair-request"

    /** Phone → watch. Payload: "coupleId|role". */
    const val SESSION = "$PREFIX/session"

    /** Phone → watch. The phone isn't linked yet. */
    const val NOT_LINKED = "$PREFIX/not-linked"

    /** Phone → watch. Vibrate a heartbeat. Payload: bpm as text. */
    const val BUZZ = "$PREFIX/buzz"

    /** Phone → watch. The partner is looking right now; measure live for a while. */
    const val LIVE_REQUEST = "$PREFIX/live"

    fun encode(session: Session): ByteArray = "${session.coupleId}|${session.role}".toByteArray()

    fun decode(bytes: ByteArray): Session? {
        val parts = String(bytes).split('|')
        if (parts.size != 2 || parts[0].isBlank() || parts[1] !in Roles.all) return null
        return Session(parts[0], parts[1])
    }
}
