package com.heartlink.shared

/**
 * What a watch knows, sent to its phone when the phone asks ([WearPaths.SYNC_REQUEST]). The phone
 * uploads it itself, so the partner still gets it when the watch's own route to the internet
 * isn't working. Times are the watch's clock.
 */
data class WatchReport(
    /** Latest heart rate the watch measured, and when. */
    val bpm: Int? = null,
    val bpmAt: Long? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    /** A sleep or wake change the watch hasn't managed to send yet, and when it happened. */
    val asleep: Boolean? = null,
    val asleepAt: Long? = null,
    /** The watch's Firebase user ID, so its phone can remove it from the couple when unlinked. */
    val uid: String? = null,
) {
    fun encode(): ByteArray = buildList {
        if (bpm != null && bpmAt != null) add("bpm=$bpm,$bpmAt")
        if (battery != null) add("battery=$battery,$charging")
        if (asleep != null && asleepAt != null) add("asleep=$asleep,$asleepAt")
        if (uid != null) add("uid=$uid")
    }.joinToString(";").toByteArray()

    companion object {
        fun decode(bytes: ByteArray): WatchReport {
            val fields = String(bytes).split(';').mapNotNull { part ->
                val (key, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                key to value.split(',')
            }.toMap()
            val bpm = fields["bpm"]?.let { v -> v.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..299 }?.let { it to v.getOrNull(1)?.toLongOrNull() } }
            val battery = fields["battery"]
            val sleep = fields["asleep"]
            return WatchReport(
                bpm = bpm?.second?.let { bpm.first },
                bpmAt = bpm?.second,
                battery = battery?.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..100 },
                charging = battery?.getOrNull(1) == "true",
                asleep = sleep?.getOrNull(1)?.toLongOrNull()?.let { sleep[0] == "true" },
                asleepAt = sleep?.getOrNull(1)?.toLongOrNull(),
                uid = fields["uid"]?.singleOrNull()?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) },
            )
        }
    }
}
