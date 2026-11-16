package com.heartlink.shared

import java.util.concurrent.TimeUnit

/** Where a sign of activity came from. */
enum class ActivityKind(val key: String, val direct: Boolean) {
    /** Phone switched on and unlocked. */
    Phone("phone", direct = true),

    /** The HeartLink watch app open. */
    WatchApp("watch", direct = true),

    /**
     * On a phone or video call, even with the screen off. Not direct use: people stay in "sleep
     * calls" (e.g. on Discord) all night, so a call alone never means awake.
     */
    Call("call", direct = false),

    /** Plugging the phone in or unplugging it with a cable: someone's hands were on it. */
    Charger("charger", direct = true),

    /** Walking, cycling or in a vehicle with the phone (Android's activity recognition). Not use. */
    Moving("moving", direct = false),
}

/** One sign of activity, from [start] to [end] (the same for a moment). Server time. */
data class ActivitySignal(val kind: ActivityKind, val start: Long, val end: Long = start)

enum class ActivityLevel { Online, Inactive, Away }

/** The verdict on how active someone is. Recomputed from the same signals as time passes. */
data class ActivityDecision(
    val level: ActivityLevel,
    /** Last time they used a phone or watch directly (any [ActivityKind.direct] signal). */
    val lastEngaged: Long?,
    /** Last time they were on the move. */
    val lastMoving: Long?,
    /** What decided [level]: the latest engaged signal for online, or what kept them from away. */
    val reason: ActivityKind?,
)

/**
 * The one place that decides online / inactive / away, from every sign of activity there is:
 *
 * - **online**: used a phone or watch directly in the last [StatusRules.INACTIVE_AFTER_MS]
 *   (unlocked phone, the watch app, a charging cable), or on a call while having used one in the
 *   last [StatusRules.AWAY_AFTER_MS]
 * - **inactive**: used one in the last [StatusRules.AWAY_AFTER_MS], on a call (however long ago
 *   they last touched anything: maybe a sleep call), or on the move in the last
 *   [StatusRules.MOVING_RECENT_MS] (out and about with their phone, so not "away")
 * - **away**: none of these
 *
 * Wearing the watch (heart-rate readings) doesn't count: it shows the watch is on, not that they're
 * around. Sleep is decided separately by [SleepModel], which takes direct use here as its evidence
 * of being awake ([engagedRuns]); calls and moving aren't. [partnerStatus] puts the two together.
 */
object ActivityModel {
    fun decide(signals: List<ActivitySignal>, now: Long): ActivityDecision {
        val past = signals.filter { it.start <= now }
        fun latest(filter: (ActivitySignal) -> Boolean) = past.filter(filter).maxByOrNull { minOf(it.end, now) }
        val direct = latest { it.kind.direct }
        val lastEngaged = direct?.let { minOf(it.end, now) }
        val lastCall = latest { it.kind == ActivityKind.Call }?.let { minOf(it.end, now) }
        val lastMoving = latest { it.kind == ActivityKind.Moving }?.let { minOf(it.end, now) }
        fun within(at: Long?, ms: Long) = at != null && now - at < ms
        // Calls are reported every 5 minutes (callAt), so one within this window is still going.
        val onCall = within(lastCall, StatusRules.INACTIVE_AFTER_MS)
        val (level, reason) = when {
            within(lastEngaged, StatusRules.INACTIVE_AFTER_MS) -> ActivityLevel.Online to direct?.kind
            onCall && within(lastEngaged, StatusRules.AWAY_AFTER_MS) -> ActivityLevel.Online to ActivityKind.Call
            within(lastEngaged, StatusRules.AWAY_AFTER_MS) -> ActivityLevel.Inactive to direct?.kind
            onCall -> ActivityLevel.Inactive to ActivityKind.Call
            within(lastMoving, StatusRules.MOVING_RECENT_MS) -> ActivityLevel.Inactive to ActivityKind.Moving
            else -> ActivityLevel.Away to null
        }
        return ActivityDecision(level, lastEngaged, lastMoving, reason)
    }

    /**
     * Direct use of a phone or watch, as runs: the sleep model's evidence of being awake. Calls
     * aren't (people fall asleep on calls), nor is moving.
     */
    fun engagedRuns(signals: List<ActivitySignal>): List<UseRun> =
        SleepModel.mergeRuns(signals.filter { it.kind.direct }.map { UseRun(it.start, it.end) })
}

/** What anyone can see of this person's activity in their presence. */
fun Presence.activitySignals(): List<ActivitySignal> = listOfNotNull(
    lastActive?.let { ActivitySignal(ActivityKind.Phone, it) },
    callAt?.let { ActivitySignal(ActivityKind.Call, it) },
    movingAt?.let { ActivitySignal(ActivityKind.Moving, it) },
)

/** This person's activity at [now], decided by [ActivityModel] from their presence. */
fun Presence.activityDecision(now: Long): ActivityDecision = ActivityModel.decide(activitySignals(), now)

/** How often a phone on the move re-reports it, so others know it's still moving. */
val MOVING_REPORT_MS: Long = TimeUnit.MINUTES.toMillis(10)
