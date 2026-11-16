package com.heartlink.shared

import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * One person's settings for [SleepModel]: how much phone use means they're up. Starts at the
 * [StatusRules] values and is tuned on their phone by [SleepLearning].
 */
data class SleepTuning(
    /** Total use in a spell that means they're up. */
    val confirmMs: Long = StatusRules.WAKE_CONFIRM_MS,
    /** How long a spell must last to mean they're up. */
    val spanMs: Long = StatusRules.WAKE_SPAN_MS,
    /** The same two after [StatusRules.FULL_SLEEP_MS] of sleep. */
    val restedConfirmMs: Long = StatusRules.RESTED_WAKE_CONFIRM_MS,
    val restedSpanMs: Long = StatusRules.RESTED_WAKE_SPAN_MS,
) {
    fun confirmMs(sleptMs: Long): Long = if (sleptMs >= StatusRules.FULL_SLEEP_MS) restedConfirmMs else confirmMs

    fun spanMs(sleptMs: Long): Long = if (sleptMs >= StatusRules.FULL_SLEEP_MS) restedSpanMs else spanMs

    companion object {
        val DEFAULT = SleepTuning()
    }
}

/**
 * A finished night, kept on the person's phone: the evidence the model had (without the finished
 * sleep itself) and when they really woke up, according to Health Connect or the Sleep API.
 */
data class NightRecord(
    /** When the watch said asleep. */
    val onset: Long,
    /** When they really woke, from [truthSource]. */
    val wokeAt: Long,
    val truthSource: SleepReason,
    val uses: List<UseRun> = emptyList(),
    val readings: List<SleepConfidence> = emptyList(),
    /** When the watch itself reported waking, if it did. */
    val watchWokeAt: Long? = null,
)

/**
 * Learns one person's [SleepTuning] from their own nights, all on their phone. Each night the
 * model's first "awake" (replayed with the evidence it had) is compared with when they really
 * woke. Saying "awake" too early is worse than too late (their partner is told they're up while
 * they're asleep), so it costs more. Each setting moves part of the way towards the value that
 * would have been best over the last [MAX_NIGHTS] nights, within safe limits, so one odd night
 * can't throw it off.
 */
object SleepLearning {
    /** Nights needed before anything changes. */
    const val MIN_NIGHTS = 3

    /** Nights kept. */
    const val MAX_NIGHTS = 14

    /** Off by less than this counts as right. */
    val TOLERANCE_MS: Long = TimeUnit.MINUTES.toMillis(5)

    /** Evidence is replayed until this long after the real wake; never saying awake by then costs the most. */
    val AFTER_WAKE_MS: Long = TimeUnit.HOURS.toMillis(3)

    /** Saying awake while still asleep costs this much more than saying it late. */
    const val EARLY_WEIGHT = 1.5

    /** Part of the way to move towards the best value each night. */
    const val LEARNING_RATE = 0.3

    private fun minutes(vararg values: Long) = values.map { TimeUnit.MINUTES.toMillis(it) }

    /** The values each setting may take. The ends are the safe limits. */
    private val CONFIRM_CHOICES = minutes(5, 8, 10, 12, 15, 20, 25, 30)
    private val SPAN_CHOICES = minutes(15, 20, 30, 45, 60, 75, 90)
    private val RESTED_CONFIRM_CHOICES = minutes(2, 3, 5, 8, 10, 15)
    private val RESTED_SPAN_CHOICES = minutes(10, 15, 20, 30, 45)

    /** When the model would have said they're up, with [tuning]: its own guess, or the watch's report if sooner. */
    fun predictedWake(night: NightRecord, tuning: SleepTuning): Long? {
        val evidence = SleepEvidence(true, night.onset, night.uses, readings = night.readings)
        val model = SleepModel.firstWake(evidence, night.wokeAt + AFTER_WAKE_MS, tuning)
        return listOfNotNull(model, night.watchWokeAt).minOrNull()
    }

    /** Predicted minus real wake time: negative means it said awake too early. Never saying it counts as [AFTER_WAKE_MS] late. */
    fun error(night: NightRecord, tuning: SleepTuning): Long {
        val predicted = predictedWake(night, tuning) ?: return AFTER_WAKE_MS
        return (predicted - night.wokeAt).coerceIn(-AFTER_WAKE_MS, AFTER_WAKE_MS)
    }

    fun cost(nights: List<NightRecord>, tuning: SleepTuning): Double = nights.sumOf { night ->
        val error = error(night, tuning)
        val off = (abs(error) - TOLERANCE_MS).coerceAtLeast(0L).toDouble()
        if (error < 0) off * EARLY_WEIGHT else off
    }

    /** Average distance from the real wake time, or null with no nights. */
    fun averageError(nights: List<NightRecord>, tuning: SleepTuning): Long? =
        if (nights.isEmpty()) null else nights.sumOf { abs(error(it, tuning)) } / nights.size

    /** [current] moved part of the way towards what would have suited [nights] best. */
    fun tune(current: SleepTuning, nights: List<NightRecord>): SleepTuning {
        val recent = nights.sortedBy { it.onset }.takeLast(MAX_NIGHTS)
        if (recent.size < MIN_NIGHTS) return current
        var tuning = current
        tuning = tuning.copy(confirmMs = step(tuning.confirmMs, CONFIRM_CHOICES) { tuning.copy(confirmMs = it).let { t -> cost(recent, t) } })
        tuning = tuning.copy(spanMs = step(tuning.spanMs, SPAN_CHOICES) { tuning.copy(spanMs = it).let { t -> cost(recent, t) } })
        tuning = tuning.copy(restedConfirmMs = step(tuning.restedConfirmMs, RESTED_CONFIRM_CHOICES) { tuning.copy(restedConfirmMs = it).let { t -> cost(recent, t) } })
        tuning = tuning.copy(restedSpanMs = step(tuning.restedSpanMs, RESTED_SPAN_CHOICES) { tuning.copy(restedSpanMs = it).let { t -> cost(recent, t) } })
        return tuning
    }

    /**
     * One setting: finds the choice with the lowest cost (on a tie, the one nearest [value], so
     * nothing drifts without a reason) and moves part of the way to it, at least a minute.
     */
    private fun step(value: Long, choices: List<Long>, cost: (Long) -> Double): Long {
        val costs = choices.associateWith(cost)
        val lowest = costs.values.minOrNull() ?: return value
        val best = costs.filterValues { it <= lowest + 1e-6 }.keys.minByOrNull { abs(it - value) } ?: return value
        // Already as good as the best: stay.
        if (cost(value) <= lowest + 1e-6) return value.coerceIn(choices.first(), choices.last())
        val minute = TimeUnit.MINUTES.toMillis(1)
        val move = ((best - value) * LEARNING_RATE).toLong()
        val stepped = when {
            abs(move) >= minute -> value + move / minute * minute
            best > value -> value + minute
            else -> value - minute
        }
        return stepped.coerceIn(choices.first(), choices.last())
    }
}
