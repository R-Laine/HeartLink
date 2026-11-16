package com.heartlink.shared

/** A stretch of continuous phone or watch use. Server time. */
data class UseRun(val start: Long, val end: Long)

/** A sleep another source has finished recording. Server time. */
data class FinishedSleep(val start: Long, val end: Long, val source: SleepReason = SleepReason.HealthConnect)

/** Android's Sleep API guess, every 10 minutes, of how likely it is that this person is asleep (0–100). */
data class SleepConfidence(val at: Long, val confidence: Int)

/** What a [SleepDecision] rests on, for logs and for working out problems. */
enum class SleepReason(val key: String) {
    Watch("watch"),
    PhoneUse("use"),
    HealthConnect("health"),
    SleepApi("sleepApi"),
    ;

    companion object {
        fun fromKey(key: String?): SleepReason = entries.firstOrNull { it.key == key } ?: Watch
    }
}

/** Everything known about one person's sleep. Only the watch's flag is required; the rest is optional. */
data class SleepEvidence(
    /** The watch's sleep detection: its latest report, and when that changed ([Presence.asleepAt]). */
    val watchAsleep: Boolean,
    val watchChangedAt: Long?,
    /** Phone and watch use. */
    val uses: List<UseRun> = emptyList(),
    /** Sleeps Health Connect or the Sleep API have recorded as finished. */
    val finishedSleeps: List<FinishedSleep> = emptyList(),
    /** Sleep API confidence readings. */
    val readings: List<SleepConfidence> = emptyList(),
)

/**
 * The verdict on whether someone is asleep. The person's own phone, which sees every source,
 * publishes it at presence/{role}/sleep; other devices fall back to deciding from what's in their
 * presence (see [Presence.sleepDecision]).
 */
data class SleepDecision(
    val state: SleepState,
    /** When this state began, as far as is known. */
    val since: Long?,
    /** The watch report ([Presence.asleepAt]) it was based on: a newer one makes it out of date. */
    val basis: Long?,
    /** For [SleepState.WokeUp]: the last use. Without more use it lapses back to asleep (see [at]). */
    val lastUse: Long? = null,
    val reason: SleepReason,
    /** When it was decided (server time). */
    val decidedAt: Long? = null,
) {
    /** The decision as it stands at [now]: "woke up" lapses back to asleep after [StatusRules.WAKE_GAP_MS] without use. */
    fun at(now: Long): SleepDecision =
        if (state == SleepState.WokeUp && lastUse != null && now - lastUse >= StatusRules.WAKE_GAP_MS) {
            copy(state = SleepState.Asleep, since = lastUse + StatusRules.WAKE_GAP_MS, lastUse = null)
        } else {
            this
        }

    /** Same verdict, ignoring when it was decided: no need to publish it again. */
    fun sameAs(other: SleepDecision?): Boolean = other != null && copy(decidedAt = null) == other.copy(decidedAt = null)
}

/**
 * The one place that decides whether someone is asleep, from every source there is. It replays
 * what happened since the watch said asleep, in time order:
 *
 * - **Watch**: says when sleep starts ([SleepEvidence.watchChangedAt]); its "awake" is final. It's
 *   slow to notice waking, and never does if it comes off to charge, so the rest corrects it.
 * - **Use** of the phone or watch → woke up. Uses less than [StatusRules.WAKE_GAP_MS] apart form one
 *   spell (a shower or breakfast doesn't end it). A spell means they're up once it holds
 *   [SleepTuning.confirmMs] of use in total or lasts [SleepTuning.spanMs] (both much less after
 *   [StatusRules.FULL_SLEEP_MS] of sleep). A spell that ends without that → asleep again. The
 *   person's phone tunes these to them (see [SleepLearning]).
 * - **A finished sleep** (Health Connect, Sleep API) ending after the watch said asleep → up since
 *   its end. One *starting* after they were up (apps split a night in two around a trip to the
 *   bathroom) → asleep again from its start.
 * - **Sleep API confidence**: after being up in the middle of a sleep (before
 *   [StatusRules.FULL_SLEEP_MS]), [ASLEEP_READINGS] readings of [ASLEEP_CONFIDENCE] or more in a
 *   row, at least [StatusRules.WAKE_GAP_MS] after the last use → back to sleep. Never after a full
 *   night: a phone left still in a dark drawer isn't sleep.
 *
 * Naps and nights follow the same rules; there's no time-of-day logic.
 */
object SleepModel {
    const val ASLEEP_CONFIDENCE = 85
    const val ASLEEP_READINGS = 2

    /** Finished sleeps this close together are one night that an app split in two (see [mergeSleeps]). */
    val SLEEP_MERGE_GAP_MS: Long = java.util.concurrent.TimeUnit.MINUTES.toMillis(90)

    fun decide(evidence: SleepEvidence, now: Long, tuning: SleepTuning = SleepTuning.DEFAULT): SleepDecision {
        val onset = evidence.watchChangedAt
        if (!evidence.watchAsleep || onset == null) {
            return SleepDecision(SleepState.Awake, onset, onset, reason = SleepReason.Watch, decidedAt = now)
        }
        // A sleep flag this old is a watch that died while they slept.
        if (now - onset >= StatusRules.ASLEEP_STALE_MS) {
            return SleepDecision(SleepState.Awake, null, onset, reason = SleepReason.Watch, decidedAt = now)
        }
        return replay(onset, evidence, now, tuning).decision(now)
    }

    /**
     * When the model would first have said "awake" for a sleep starting at [evidence]'s
     * watchChangedAt, replaying the evidence up to [until]. Null if it never would have.
     */
    fun firstWake(evidence: SleepEvidence, until: Long, tuning: SleepTuning): Long? {
        val onset = evidence.watchChangedAt ?: return null
        return replay(onset, evidence, until, tuning).firstAwake
    }

    private fun replay(onset: Long, evidence: SleepEvidence, now: Long, tuning: SleepTuning): Night {

        val events = buildList {
            mergeRuns(evidence.uses).forEach { run ->
                if (run.end > onset && run.start <= now) add(Event.Use(maxOf(run.start, onset + 1), minOf(run.end, now)))
            }
            evidence.finishedSleeps.forEach { sleep ->
                if (sleep.end <= sleep.start || sleep.end <= onset) return@forEach
                if (sleep.start > onset && sleep.start <= now) add(Event.Slept(sleep.start, sleep.source))
                if (sleep.end <= now) add(Event.Woke(sleep.end, sleep.source))
            }
            evidence.readings.forEach { reading ->
                if (reading.at > onset && reading.at <= now) add(Event.Reading(reading.at, reading.confidence))
            }
        }.sortedBy { it.at }

        val night = Night(onset, tuning)
        events.forEach { event ->
            night.elapse(event.at)
            night.handle(event)
        }
        night.elapse(now)
        return night
    }

    /** Joins overlapping or back-to-back runs (the phone and the watch app can both report the same use). */
    fun mergeRuns(runs: List<UseRun>): List<UseRun> {
        val merged = ArrayList<UseRun>()
        runs.filter { it.end >= it.start }.sortedBy { it.start }.forEach { run ->
            val last = merged.lastOrNull()
            if (last != null && run.start <= last.end + StatusRules.USE_TICK_MS) {
                merged[merged.lastIndex] = UseRun(last.start, maxOf(last.end, run.end))
            } else {
                merged += run
            }
        }
        return merged
    }

    /**
     * Joins finished sleeps less than [SLEEP_MERGE_GAP_MS] apart (a night an app recorded as two
     * sessions), so the end of the last one is when the night really ended.
     */
    fun mergeSleeps(sleeps: List<FinishedSleep>): List<FinishedSleep> {
        val merged = ArrayList<FinishedSleep>()
        sleeps.filter { it.end > it.start }.sortedBy { it.start }.forEach { sleep ->
            val last = merged.lastOrNull()
            if (last != null && sleep.start - last.end < SLEEP_MERGE_GAP_MS) {
                merged[merged.lastIndex] = last.copy(end = maxOf(last.end, sleep.end))
            } else {
                merged += sleep
            }
        }
        return merged
    }

    private sealed class Event(val at: Long) {
        class Use(start: Long, val end: Long) : Event(start)
        class Woke(at: Long, val source: SleepReason) : Event(at)
        class Slept(at: Long, val source: SleepReason) : Event(at)
        class Reading(at: Long, val confidence: Int) : Event(at)
    }

    /** The state machine for one sleep, from [onset] (when the watch said asleep). */
    private class Night(private val onset: Long, private val tuning: SleepTuning) {
        /** When it first decided "awake" (and the time it said they woke). */
        var firstAwake: Long? = null
        var state = SleepState.Asleep
        var since: Long = onset
        var reason = SleepReason.Watch

        // While woken up: the spell of use, and the asleep state to return to if it ends.
        var spellStart = 0L
        var spellLast = 0L
        var spellUsed = 0L
        var asleepSince = onset
        var asleepReason = SleepReason.Watch

        var lastUse = onset
        var drowsyStart: Long? = null
        var drowsyCount = 0

        /** Time passing until [t]: a spell with no use for [StatusRules.WAKE_GAP_MS] ends. */
        fun elapse(t: Long) {
            if (state == SleepState.WokeUp && t - spellLast >= StatusRules.WAKE_GAP_MS) {
                state = SleepState.Asleep
                since = asleepSince
                reason = asleepReason
            }
        }

        fun handle(event: Event) {
            when (event) {
                is Event.Use -> use(event.at, event.end)
                is Event.Woke -> woke(event.at, event.source)
                is Event.Slept -> slept(event.at, event.source)
                is Event.Reading -> reading(event.at, event.confidence)
            }
        }

        /** Another source recorded a sleep starting at [at]: asleep again, whatever use came before. */
        private fun slept(at: Long, source: SleepReason) {
            drowsyStart = null
            drowsyCount = 0
            if (state == SleepState.Asleep) return
            state = SleepState.Asleep
            since = at
            reason = source
        }

        private fun use(start: Long, end: Long) {
            lastUse = maxOf(lastUse, end)
            drowsyStart = null
            drowsyCount = 0
            when (state) {
                SleepState.Awake -> return
                SleepState.Asleep -> {
                    asleepSince = since
                    asleepReason = reason
                    state = SleepState.WokeUp
                    since = start
                    reason = SleepReason.PhoneUse
                    spellStart = start
                    spellLast = start
                    spellUsed = 0L
                }
                SleepState.WokeUp -> Unit
            }
            val usedBefore = spellUsed
            // A run of n minutes of pings is n + 1 uses' worth.
            spellUsed += (end - start) + StatusRules.USE_TICK_MS
            spellLast = maxOf(spellLast, end)

            val slept = spellStart - onset
            val need = tuning.confirmMs(slept)
            val byUse = if (spellUsed >= need) start + (need - usedBefore - StatusRules.USE_TICK_MS).coerceAtLeast(0L) else null
            val spanEnd = spellStart + tuning.spanMs(slept)
            val bySpan = if (end >= spanEnd) maxOf(start, spanEnd) else null
            val upAt = listOfNotNull(byUse, bySpan).minOrNull() ?: return
            state = SleepState.Awake
            since = upAt
            reason = SleepReason.PhoneUse
            if (firstAwake == null) firstAwake = upAt
        }

        private fun woke(at: Long, source: SleepReason) {
            drowsyStart = null
            drowsyCount = 0
            if (state != SleepState.Awake) {
                state = SleepState.Awake
                since = at
                reason = source
                if (firstAwake == null) firstAwake = at
            } else if (at < since) {
                // A finished sleep says when they really woke; use it if it's earlier.
                since = at
                reason = source
            }
        }

        private fun reading(at: Long, confidence: Int) {
            // Only after being up in the middle of a sleep.
            if (state != SleepState.Awake || since - onset >= StatusRules.FULL_SLEEP_MS) return
            if (confidence < ASLEEP_CONFIDENCE) {
                drowsyStart = null
                drowsyCount = 0
                return
            }
            if (drowsyStart == null) drowsyStart = at
            drowsyCount++
            if (drowsyCount >= ASLEEP_READINGS && at - lastUse >= StatusRules.WAKE_GAP_MS) {
                state = SleepState.Asleep
                since = drowsyStart ?: at
                reason = SleepReason.SleepApi
                drowsyStart = null
                drowsyCount = 0
            }
        }

        fun decision(now: Long) = SleepDecision(
            state = state,
            since = since,
            basis = onset,
            lastUse = if (state == SleepState.WokeUp) spellLast else null,
            reason = reason,
            decidedAt = now,
        )
    }
}

/**
 * What anyone can see of this person's sleep evidence in their presence: the watch's flag, and the
 * current stretch of use (from [Presence.wokeAt] to [Presence.lastActive]).
 */
fun Presence.visibleEvidence(): SleepEvidence {
    val active = lastActive
    val uses = if (active == null) emptyList() else listOf(UseRun(wokeAt?.takeIf { it <= active } ?: active, active))
    return SleepEvidence(watchAsleep = asleep, watchChangedAt = asleepAt, uses = uses)
}

/**
 * This person's sleep at [now]: the decision their phone published if it's up to date, otherwise
 * one made here from what their presence shows.
 */
fun Presence.sleepDecision(now: Long): SleepDecision {
    val fallback = { SleepModel.decide(visibleEvidence(), now) }
    val published = sleep ?: return fallback()
    // Out of date: the watch has reported since, or it says awake (that's final).
    if (!asleep || asleepAt == null || published.basis != asleepAt) return fallback()
    if (now - asleepAt >= StatusRules.ASLEEP_STALE_MS) return fallback()
    // Their phone said asleep or stirring, but a device has been used since that the phone hasn't
    // accounted for (e.g. the watch app while the phone is off): don't let the verdict lapse back
    // to asleep on stale news.
    val decidedAt = published.decidedAt ?: 0L
    val accountedFor = maxOf(decidedAt, published.lastUse ?: 0L)
    if (published.state != SleepState.Awake && (lastActive ?: 0L) > accountedFor + 2 * StatusRules.USE_TICK_MS) {
        val local = fallback()
        if (local.state != SleepState.Asleep) return local
    }
    return published.at(now)
}
