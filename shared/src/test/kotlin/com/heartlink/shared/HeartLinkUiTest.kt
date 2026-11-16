package com.heartlink.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartLinkUiTest {
    @Test
    fun ageText_buckets() {
        assertEquals("live", ageText(0))
        assertEquals("live", ageText(19_999))
        assertEquals("20s ago", ageText(20_000))
        assertEquals("59s ago", ageText(59_999))
        assertEquals("1 min ago", ageText(60_000))
        assertEquals("59 min ago", ageText(3_599_999))
        assertEquals("1 hr ago", ageText(3_600_000))
    }

    @Test
    fun beatPeriod_isClampedAndDefaults() {
        assertEquals(1000, beatPeriodMs(null))
        assertEquals(1000, beatPeriodMs(0))
        assertEquals(1000, beatPeriodMs(60))
        assertEquals(500, beatPeriodMs(120))
        assertEquals(250, beatPeriodMs(400))
        assertEquals(2000, beatPeriodMs(10))
    }

    @Test
    fun heartbeatScale_lubDubThenRests() {
        assertEquals(1f, heartbeatScale(0f, 1000), 0.0001f)
        assertEquals(1.3f, heartbeatScale(0.07f, 1000), 0.0001f) // lub
        assertEquals(1.06f, heartbeatScale(0.18f, 1000), 0.0001f) // gap
        assertEquals(1.18f, heartbeatScale(0.23f, 1000), 0.0001f) // dub
        assertEquals(1f, heartbeatScale(0.5f, 1000), 0.0001f)
        assertEquals(1f, heartbeatScale(0.9f, 1000), 0.0001f)
        // Fast beats squeeze the lub-dub in rather than cutting it off.
        assertEquals(1.18f, heartbeatScale(230f / 450f, 300), 0.0001f)
    }

    @Test
    fun roles_partnerOf() {
        assertEquals(Roles.B, Roles.partnerOf(Roles.A))
        assertEquals(Roles.A, Roles.partnerOf(Roles.B))
        assertEquals(Roles.B, Session("ABCDEF", Roles.A).partnerRole)
    }

    @Test
    fun inviteCode_generatesValidCodes() {
        repeat(200) {
            val code = InviteCode.generate()
            assertEquals(InviteCode.LENGTH, code.length)
            assertTrue(code, InviteCode.isValid(code))
        }
    }

    @Test
    fun inviteCode_normalizesTypedInput() {
        assertEquals("K7QM2X", InviteCode.normalize(" k7q-m2x "))
        assertTrue(InviteCode.isValid(InviteCode.normalize("k7q m2x")))
        assertFalse(InviteCode.isValid("K7QM2"))      // too short
        assertFalse(InviteCode.isValid("K7QM20"))     // 0 isn't in the alphabet
        assertFalse(InviteCode.isValid("us-two"))
    }

    @Test
    fun names_areTrimmedAndCapped() {
        assertEquals("Sam Lee", Names.clean("  Sam   Lee "))
        assertEquals(Names.MAX_LENGTH, Names.clean("x".repeat(100)).length)
        assertEquals("", Names.clean("   "))
    }

    @Test
    fun statusText_labelsNonWatchSources() {
        assertEquals("live", statusText(5_000, Sources.WATCH))
        assertEquals("live", statusText(5_000, null))
        assertEquals("14 min ago · Health Connect", statusText(14 * 60_000L, Sources.HEALTH_CONNECT))
        assertEquals("live · test beat", statusText(1_000, Sources.TEST))
    }

    @Test
    fun wearPaths_roundTripSession() {
        val session = Session("-NxAbc123_def", Roles.B)
        assertEquals(session, WearPaths.decode(WearPaths.encode(session)))
        assertEquals(null, WearPaths.decode("nope".toByteArray()))
        assertEquals(null, WearPaths.decode("id|c".toByteArray()))
        assertEquals(null, WearPaths.decode("|a".toByteArray()))
    }

    private val now = 1_800_000_000_000L
    private fun minutesAgo(m: Long) = now - m * 60_000L

    @Test
    fun isAsleep_forQuietHours() {
        val slept = Presence(connected = true, asleep = true, asleepAt = minutesAgo(120), lastActive = minutesAgo(130))
        assertTrue(slept.isAsleep(now))
        // Using the phone right now: not asleep.
        assertFalse(slept.copy(lastActive = minutesAgo(2), wokeAt = minutesAgo(3)).isAsleep(now))
        // Put it down 12 minutes ago: partner still sees "woke up", but heartbeats stay quiet.
        val settled = slept.copy(lastActive = minutesAgo(12), wokeAt = minutesAgo(14))
        assertEquals(SleepState.WokeUp, settled.sleepState(now))
        assertTrue(settled.isAsleep(now))
        // Their phone decided they're up.
        val up = SleepDecision(SleepState.Awake, minutesAgo(5), basis = minutesAgo(120), reason = SleepReason.PhoneUse, decidedAt = minutesAgo(5))
        assertFalse(slept.copy(sleep = up).isAsleep(now))
    }

    private fun run(fromAgo: Long, toAgo: Long) = UseRun(minutesAgo(fromAgo), minutesAgo(toAgo))
    private fun readings(vararg pairs: Pair<Long, Int>) = pairs.map { (m, c) -> SleepConfidence(minutesAgo(m), c) }
    private fun decide(
        asleepAt: Long,
        vararg uses: UseRun,
        finished: List<FinishedSleep> = emptyList(),
        readings: List<SleepConfidence> = emptyList(),
        at: Long = now,
    ) = SleepModel.decide(SleepEvidence(true, asleepAt, uses.toList(), finished, readings), at)

    @Test
    fun sleepModel_watch() {
        // The watch says awake: final.
        val awake = SleepModel.decide(SleepEvidence(false, minutesAgo(30), listOf(run(20, 10))), now)
        assertEquals(SleepState.Awake, awake.state)
        assertEquals(SleepReason.Watch, awake.reason)
        // Asleep with nothing else known.
        val asleep = decide(minutesAgo(120))
        assertEquals(SleepState.Asleep, asleep.state)
        assertEquals(minutesAgo(120), asleep.since)
        // A flag from a watch that died overnight is ignored.
        assertEquals(SleepState.Awake, decide(minutesAgo(20 * 60)).state)
        // Use from before falling asleep doesn't count.
        assertEquals(SleepState.Asleep, decide(minutesAgo(30), run(50, 31)).state)
    }

    @Test
    fun sleepModel_use() {
        val night = minutesAgo(5 * 60)
        // 15 minutes of use in one go -> up, from the 15th minute.
        val up = decide(night, run(60, 41))
        assertEquals(SleepState.Awake, up.state)
        assertEquals(minutesAgo(46), up.since)
        assertEquals(SleepReason.PhoneUse, up.reason)
        // A few minutes of use -> woke up, lapsing back to asleep after 30 minutes without use.
        val stirring = decide(night, run(10, 5))
        assertEquals(SleepState.WokeUp, stirring.state)
        assertEquals(minutesAgo(10), stirring.since)
        assertEquals(SleepState.Asleep, stirring.at(now + 25 * 60_000L).state)
        assertEquals(SleepState.Asleep, decide(night, run(60, 55)).state)
        // A morning: 5 minutes on waking, a 25-minute shower, 5 minutes, breakfast, then back: up
        // when the spell reaches 45 minutes.
        val morning = decide(night, run(90, 86), run(60, 56), run(35, 31))
        assertEquals(SleepState.Awake, morning.state)
        assertEquals(minutesAgo(35), morning.since)
        // Night-time looks, each spell ending before it adds up: not up.
        assertEquals(SleepState.WokeUp, decide(night, run(200, 199), run(140, 133), run(60, 58), run(25, 23)).state)
        // The phone and the watch app reporting the same use don't count it twice.
        assertEquals(SleepState.WokeUp, decide(night, run(12, 5), run(11, 6), run(10, 5)).state)
    }

    @Test
    fun sleepModel_afterAFullNight() {
        val night = minutesAgo(8 * 60)
        // After 6 hours asleep, 5 minutes of use is enough...
        assertEquals(minutesAgo(26), decide(night, run(30, 21)).since)
        assertEquals(SleepState.Awake, decide(night, run(30, 21)).state)
        // ...or a spell lasting 20 minutes: a look on waking, then back after getting ready.
        val spell = decide(night, run(40, 39), run(20, 19))
        assertEquals(SleepState.Awake, spell.state)
        assertEquals(minutesAgo(20), spell.since)
        // Just short of 6 hours, the same 5 minutes isn't enough.
        assertEquals(SleepState.WokeUp, decide(minutesAgo(5 * 60 + 50), run(30, 26)).state)
    }

    @Test
    fun sleepModel_finishedSleep() {
        val night = minutesAgo(8 * 60)
        // Samsung Health saved a night that ended 20 minutes ago: awake since then.
        val saved = FinishedSleep(minutesAgo(8 * 60 + 10), minutesAgo(20))
        val up = decide(night, finished = listOf(saved))
        assertEquals(SleepState.Awake, up.state)
        assertEquals(minutesAgo(20), up.since)
        assertEquals(SleepReason.HealthConnect, up.reason)
        // Its end is when they really woke, even if phone use confirmed it later.
        val both = decide(night, run(30, 21), finished = listOf(FinishedSleep(minutesAgo(500), minutesAgo(40))))
        assertEquals(minutesAgo(40), both.since)
        // A sleep that ended before this one began (last night, before today's nap) doesn't count.
        assertEquals(SleepState.Asleep, decide(minutesAgo(10), finished = listOf(saved)).state)
        // Nonsense times are ignored.
        assertEquals(SleepState.Asleep, decide(night, finished = listOf(FinishedSleep(minutesAgo(10), minutesAgo(20)))).state)
        assertEquals(SleepState.Asleep, decide(night, finished = listOf(FinishedSleep(minutesAgo(60), now + 600_000))).state)
    }

    @Test
    fun sleepModel_splitNight() {
        // Samsung Health saved the night as two sessions around a trip to the bathroom at 3 am.
        val night = minutesAgo(8 * 60)
        val first = FinishedSleep(minutesAgo(8 * 60 + 5), minutesAgo(5 * 60))
        val second = FinishedSleep(minutesAgo(5 * 60 - 10), minutesAgo(20))
        // Only the first half known yet: up since it ended...
        assertEquals(minutesAgo(5 * 60), decide(night, finished = listOf(first)).since)
        // ...until the second half arrives: asleep again from its start, then up from its end.
        val asleepAgain = decide(night, finished = listOf(first, second), at = minutesAgo(3 * 60))
        assertEquals(SleepState.Asleep, asleepAgain.state)
        assertEquals(minutesAgo(5 * 60 - 10), asleepAgain.since)
        assertEquals(SleepReason.HealthConnect, asleepAgain.reason)
        val up = decide(night, finished = listOf(first, second))
        assertEquals(SleepState.Awake, up.state)
        assertEquals(minutesAgo(20), up.since)
        // Phone use in the gap doesn't keep them up either.
        assertEquals(SleepState.Asleep, decide(night, run(5 * 60 - 2, 5 * 60 - 1), finished = listOf(first, second), at = minutesAgo(3 * 60)).state)
        // The two halves count as one night when checking when it really ended.
        assertEquals(listOf(FinishedSleep(first.start, second.end)), SleepModel.mergeSleeps(listOf(second, first)))
        // A nap hours later is its own sleep.
        val nap = FinishedSleep(minutesAgo(5), now + 60 * 60_000L)
        assertEquals(2, SleepModel.mergeSleeps(listOf(first, second, nap)).size)
    }

    @Test
    fun sleepModel_backToSleep() {
        // Asleep 3 hours ago, got up and scrolled for half an hour until 70 minutes ago, then quiet.
        val night = minutesAgo(180)
        val settled = readings(75L to 20, 55L to 60, 45L to 90, 35L to 92, 25L to 95)
        val back = decide(night, run(100, 70), readings = settled)
        assertEquals(SleepState.Asleep, back.state)
        assertEquals(minutesAgo(45), back.since)
        assertEquals(SleepReason.SleepApi, back.reason)
        // Picking the phone up again afterwards: woke up again.
        assertEquals(SleepState.WokeUp, decide(night, run(100, 70), run(10, 5), readings = settled).state)
        // One asleep-looking reading isn't enough.
        assertEquals(SleepState.Awake, decide(night, run(100, 70), readings = readings(45L to 40, 35L to 50, 25L to 95)).state)
        // Readings too soon after the last use don't count until 30 minutes have passed.
        assertEquals(minutesAgo(50), decide(night, run(100, 70), readings = readings(50L to 90, 45L to 92, 35L to 95)).since)
        // After a full night, being up is final (a phone left in a dark drawer isn't sleep).
        assertEquals(SleepState.Awake, decide(minutesAgo(9 * 60), run(100, 70), readings = settled).state)
    }

    /** A night 8 hours long, ending [daysAgo] days ago, with use given as minutes after waking. */
    private fun night(daysAgo: Long, vararg usesAfterWake: Pair<Long, Long>, watchWokeAfter: Long? = null): NightRecord {
        val woke = now - daysAgo * 24 * 60 * 60_000L
        val onset = woke - 8 * 60 * 60_000L
        return NightRecord(
            onset = onset,
            wokeAt = woke,
            truthSource = SleepReason.HealthConnect,
            uses = usesAfterWake.map { (from, to) -> UseRun(woke + from * 60_000L, woke + to * 60_000L) },
            watchWokeAt = watchWokeAfter?.let { woke + it * 60_000L },
        )
    }

    @Test
    fun sleepLearning_scoring() {
        // Picks up the phone on waking for 6 minutes: with the defaults, up after 5 of them.
        val n = night(1, 0L to 6L)
        assertEquals(4 * 60_000L, SleepLearning.error(n, SleepTuning.DEFAULT))
        // The watch reporting waking first wins.
        assertEquals(2 * 60_000L, SleepLearning.error(night(1, 0L to 6L, watchWokeAfter = 2), SleepTuning.DEFAULT))
        // Never said awake: counted as 3 hours late.
        assertEquals(SleepLearning.AFTER_WAKE_MS, SleepLearning.error(night(1, 0L to 2L), SleepTuning.DEFAULT))
        // Within 5 minutes costs nothing; saying awake too early costs more than late.
        assertEquals(0.0, SleepLearning.cost(listOf(n), SleepTuning.DEFAULT), 0.001)
        val early = night(1, -40L to -10L) // scrolled for half an hour before really waking
        val late = night(1, 0L to 40L).copy(watchWokeAt = null)
        assertTrue(SleepLearning.cost(listOf(early), SleepTuning.DEFAULT) > 0)
        assertTrue(SleepLearning.error(early, SleepTuning.DEFAULT) < 0)
        assertEquals(0.0, SleepLearning.cost(listOf(late), SleepTuning.DEFAULT), 0.001)
    }

    @Test
    fun sleepLearning_adjustsToSomeoneWhoBarelyUsesTheirPhone() {
        // Wakes, glances at the phone for 2 minutes, then nothing until much later.
        val nights = (1L..5L).map { night(it, 0L to 1L, 150L to 151L) }
        var tuning = SleepTuning.DEFAULT
        // Nothing changes with too few nights.
        assertEquals(tuning, SleepLearning.tune(tuning, nights.take(2)))
        repeat(5) { tuning = SleepLearning.tune(tuning, nights) }
        // Needs less use after a full night now, and guesses better than the starting settings.
        assertTrue(tuning.restedConfirmMs < SleepTuning.DEFAULT.restedConfirmMs)
        assertTrue(SleepLearning.averageError(nights, tuning)!! < SleepLearning.averageError(nights, SleepTuning.DEFAULT)!!)
        // Never beyond the safe limits.
        repeat(20) { tuning = SleepLearning.tune(tuning, nights) }
        assertTrue(tuning.restedConfirmMs >= 2 * 60_000L)
    }

    @Test
    fun sleepLearning_staysPutWhenAlreadyRight() {
        val nights = (1L..6L).map { night(it, 0L to 10L) }
        assertEquals(SleepTuning.DEFAULT, SleepLearning.tune(SleepTuning.DEFAULT, nights))
    }

    @Test
    fun sleepLearning_backsOffAfterEarlyGuesses() {
        // Scrolls in bed for 8 minutes about 40 minutes before really getting up, every morning.
        val nights = (1L..5L).map { night(it, -40L to -32L, 0L to 10L) }
        var tuning = SleepTuning.DEFAULT
        repeat(6) { tuning = SleepLearning.tune(tuning, nights) }
        assertTrue(tuning.restedConfirmMs > SleepTuning.DEFAULT.restedConfirmMs)
        assertTrue(SleepLearning.cost(nights, tuning) < SleepLearning.cost(nights, SleepTuning.DEFAULT))
    }

    @Test
    fun activityModel_levels() {
        fun at(kind: ActivityKind, from: Long, to: Long = from) = ActivitySignal(kind, minutesAgo(from), minutesAgo(to))
        // Nothing known: away.
        assertEquals(ActivityLevel.Away, ActivityModel.decide(emptyList(), now).level)
        // Any kind of use: online, then inactive, then away.
        assertEquals(ActivityLevel.Online, ActivityModel.decide(listOf(at(ActivityKind.Phone, 5)), now).level)
        assertEquals(ActivityLevel.Inactive, ActivityModel.decide(listOf(at(ActivityKind.Phone, 30)), now).level)
        assertEquals(ActivityLevel.Away, ActivityModel.decide(listOf(at(ActivityKind.Phone, 61)), now).level)
        // A long call with the screen off keeps them online, and says why.
        val call = ActivityModel.decide(listOf(at(ActivityKind.Phone, 50), at(ActivityKind.Call, 45, 1)), now)
        assertEquals(ActivityLevel.Online, call.level)
        assertEquals(ActivityKind.Call, call.reason)
        // The call itself isn't use.
        assertEquals(minutesAgo(50), call.lastEngaged)
        // Out and about with the phone: inactive rather than away, but never online.
        val walking = ActivityModel.decide(listOf(at(ActivityKind.Phone, 120), at(ActivityKind.Moving, 40, 5)), now)
        assertEquals(ActivityLevel.Inactive, walking.level)
        assertEquals(ActivityKind.Moving, walking.reason)
        assertEquals(ActivityLevel.Away, ActivityModel.decide(listOf(at(ActivityKind.Moving, 40, 20)), now).level)
        // Signals in the future (a clock ahead) don't count yet.
        assertEquals(ActivityLevel.Away, ActivityModel.decide(listOf(ActivitySignal(ActivityKind.Phone, now + 600_000)), now).level)
    }

    @Test
    fun activityModel_sleepCalls() {
        // Fell asleep on a Discord call at midnight; still connected at 4 am.
        val night = minutesAgo(4 * 60)
        val signals = listOf(
            ActivitySignal(ActivityKind.Phone, night - 30 * 60_000L, night),
            ActivitySignal(ActivityKind.Call, night - 60 * 60_000L, minutesAgo(1)),
        )
        // Asleep: the call isn't evidence of being awake...
        assertEquals(SleepState.Asleep, SleepModel.decide(SleepEvidence(true, night, ActivityModel.engagedRuns(signals)), now).state)
        // ...and without a watch, the call alone shows inactive, not online.
        val activity = ActivityModel.decide(signals, now)
        assertEquals(ActivityLevel.Inactive, activity.level)
        assertEquals(ActivityKind.Call, activity.reason)
        // The partner sees asleep, from what the presence shows.
        val p = Presence(connected = true, asleep = true, asleepAt = night, lastActive = night - 60_000L, callAt = minutesAgo(3))
        assertEquals(PartnerStatus.Asleep, partnerStatus(p, null, now))
        assertEquals(PartnerStatus.Inactive, partnerStatus(p.copy(asleep = false), null, now))
        // A call that has ended no longer counts.
        assertEquals(PartnerStatus.Away, partnerStatus(p.copy(asleep = false, callAt = minutesAgo(15)), null, now))
    }

    @Test
    fun activityModel_feedsSleep() {
        val signals = listOf(
            ActivitySignal(ActivityKind.Phone, minutesAgo(30), minutesAgo(25)),
            ActivitySignal(ActivityKind.Call, minutesAgo(25), minutesAgo(16)),
            ActivitySignal(ActivityKind.Moving, minutesAgo(15), minutesAgo(5)),
        )
        // Only direct use counts as being awake: not calls, not being carried around.
        assertEquals(listOf(UseRun(minutesAgo(30), minutesAgo(25))), ActivityModel.engagedRuns(signals))
        val decision = SleepModel.decide(SleepEvidence(true, minutesAgo(3 * 60), ActivityModel.engagedRuns(signals)), now)
        assertEquals(SleepState.WokeUp, decision.state)
        // 15 minutes of phone use is still up.
        val phone = listOf(ActivitySignal(ActivityKind.Phone, minutesAgo(30), minutesAgo(15)))
        assertEquals(SleepState.Awake, SleepModel.decide(SleepEvidence(true, minutesAgo(3 * 60), ActivityModel.engagedRuns(phone)), now).state)
        // On the partner's side, from presence: moving shows as inactive rather than away.
        val p = Presence(connected = true, lastActive = minutesAgo(90), movingAt = minutesAgo(5))
        assertEquals(PartnerStatus.Inactive, partnerStatus(p, null, now))
        assertEquals(PartnerStatus.Away, partnerStatus(p.copy(movingAt = minutesAgo(20)), null, now))
    }

    @Test
    fun sleepDecision_publishedAndFallback() {
        val p = Presence(connected = true, asleep = true, asleepAt = minutesAgo(180), lastActive = minutesAgo(70), wokeAt = minutesAgo(72))
        // Without a published verdict, decided from what the presence shows.
        assertEquals(SleepState.Asleep, p.sleepState(now))
        // Their phone's verdict wins while it's about the same watch report.
        val backAsleep = SleepDecision(SleepState.Asleep, minutesAgo(45), minutesAgo(180), reason = SleepReason.SleepApi, decidedAt = minutesAgo(25))
        val up = backAsleep.copy(state = SleepState.Awake, since = minutesAgo(90), reason = SleepReason.HealthConnect)
        assertEquals(SleepState.Awake, p.copy(sleep = up).sleepState(now))
        assertEquals(SleepReason.SleepApi, p.copy(sleep = backAsleep).sleepDecision(now).reason)
        // A newer watch report makes it out of date.
        assertEquals(SleepState.Asleep, p.copy(sleep = up, asleepAt = minutesAgo(30)).sleepState(now))
        assertEquals(SleepState.Awake, p.copy(sleep = backAsleep, asleep = false).sleepState(now))
        // "Woke up" lapses by itself, even if their phone can't publish again.
        val stirring = backAsleep.copy(state = SleepState.WokeUp, since = minutesAgo(50), lastUse = minutesAgo(40))
        assertEquals(SleepState.Asleep, p.copy(sleep = stirring).sleepState(now))
        // Used a device their phone hasn't accounted for (watch app, phone off): believe the use.
        val watchUse = p.copy(sleep = backAsleep, lastActive = minutesAgo(2), wokeAt = minutesAgo(4))
        assertEquals(SleepState.WokeUp, watchUse.sleepState(now))
        // The same while their phone said "woke up" but then went quiet: it mustn't lapse to asleep.
        assertEquals(SleepState.WokeUp, watchUse.copy(sleep = stirring).sleepState(now))
        // Once their phone said awake, that stands.
        assertEquals(SleepState.Awake, watchUse.copy(sleep = up).sleepState(now))
    }

    @Test
    fun partnerStatus_rules() {
        val active = Presence(connected = true, lastActive = minutesAgo(1))
        assertEquals(PartnerStatus.Online, partnerStatus(active, null, now))
        assertEquals(PartnerStatus.Inactive, partnerStatus(active.copy(lastActive = minutesAgo(30)), null, now))
        assertEquals(PartnerStatus.Away, partnerStatus(active.copy(lastActive = minutesAgo(61)), null, now))
        assertEquals(PartnerStatus.NoConnection, partnerStatus(active.copy(connected = false), null, now))
        val slept = active.copy(lastActive = minutesAgo(90), asleep = true, asleepAt = minutesAgo(60))
        assertEquals(PartnerStatus.Asleep, partnerStatus(slept, null, now))
        // Up in the night: using the phone while the watch still says asleep shows as inactive.
        val stirring = slept.copy(lastActive = minutesAgo(1), wokeAt = minutesAgo(5))
        assertEquals(PartnerStatus.Inactive, partnerStatus(stirring, null, now))
        // Put the phone down: still stirring (inactive) for a while (a shower shouldn't read as asleep)...
        assertEquals(PartnerStatus.Inactive, partnerStatus(stirring.copy(lastActive = minutesAgo(15), wokeAt = minutesAgo(20)), null, now))
        // ...then back to asleep.
        assertEquals(PartnerStatus.Asleep, partnerStatus(stirring.copy(lastActive = minutesAgo(31), wokeAt = minutesAgo(36)), null, now))
        // Using it for 15 minutes -> really up (the watch missed waking).
        assertEquals(PartnerStatus.Online, partnerStatus(stirring.copy(wokeAt = minutesAgo(40)), null, now))
        assertEquals(PartnerStatus.Inactive, partnerStatus(stirring.copy(lastActive = minutesAgo(20), wokeAt = minutesAgo(55)), null, now))
        assertEquals(PartnerStatus.Asleep, partnerStatus(stirring.copy(lastActive = minutesAgo(61), wokeAt = minutesAgo(100)), null, now))
        // Once their phone has decided they're up, they stay up however long they leave it,
        // until the watch reports sleep again.
        val up = stirring.copy(
            asleepAt = minutesAgo(180),
            lastActive = minutesAgo(61),
            wokeAt = minutesAgo(100),
            sleep = SleepDecision(SleepState.Awake, minutesAgo(80), minutesAgo(180), reason = SleepReason.PhoneUse, decidedAt = minutesAgo(80)),
        )
        assertEquals(PartnerStatus.Away, partnerStatus(up, null, now))
        assertEquals(PartnerStatus.Asleep, partnerStatus(up.copy(asleepAt = minutesAgo(10)), null, now))
        // Naps and nights follow the same rules, whatever the time of day.
        assertEquals(PartnerStatus.Inactive, partnerStatus(stirring.copy(timeZone = "Europe/London"), null, now))
        assertEquals(PartnerStatus.Inactive, partnerStatus(stirring.copy(timeZone = "America/Los_Angeles"), null, now))
        // A stale sleep flag (watch died overnight) is ignored.
        assertEquals(PartnerStatus.Online, partnerStatus(active.copy(asleep = true, asleepAt = minutesAgo(20 * 60)), null, now))
        // Phone offline but the watch is still uploading → reachable.
        val watchBeat = Reading(70, minutesAgo(1), Sources.WATCH)
        assertEquals(PartnerStatus.Online, partnerStatus(active.copy(connected = false), watchBeat, now))
        // Test beats don't count as proof of life.
        assertEquals(PartnerStatus.NoConnection, partnerStatus(active.copy(connected = false), watchBeat.copy(source = Sources.TEST), now))
        assertEquals(PartnerStatus.Unknown, partnerStatus(null, null, now))
    }

    @Test
    fun localTime_andDifference() {
        val london = java.time.ZoneId.of("Europe/London")
        assertEquals(null, localTimeText(null, now))
        assertEquals(null, localTimeText("Not/AZone", now))
        assertTrue(localTimeText("Asia/Tokyo", now) != null)
        assertEquals(null, timeDifferenceText("Europe/London", now, london))
        assertEquals("+9h", timeDifferenceText("Asia/Tokyo", now, java.time.ZoneId.of("UTC")))
        // Newfoundland is UTC−3:30 in winter; `now` is in January 2027.
        assertEquals("−3h 30m", timeDifferenceText("America/St_Johns", now, java.time.ZoneId.of("UTC")))
    }

    @Test
    fun heartbeatWaveform_matchesBpm() {
        val (timings, amplitudes) = Vibration.heartbeatWaveform(bpm = 60, beats = 4)
        assertEquals(timings.size, amplitudes.size)
        assertEquals(16, timings.size)
        // After the first beat, each beat (rest + lub + gap + dub) lasts one period: 1000 ms at 60 bpm.
        assertEquals(1000L, timings.slice(4 until 8).sum())
        assertEquals(0, amplitudes[0])
        assertTrue(amplitudes[1] > 0)
    }

    @Test
    fun messages_clean() {
        assertEquals("hi", Messages.clean("  hi  "))
        assertEquals(Messages.MAX_LENGTH, Messages.clean("x".repeat(500)).length)
    }

    @Test
    fun battery_reportingThreshold() {
        val at = now
        val base = BatteryState(50, charging = false)
        assertTrue(Battery.shouldReport(null, 0L, base, at))
        assertFalse(Battery.shouldReport(base, at, base.copy(level = 49), at + 60_000))
        assertTrue(Battery.shouldReport(base, at, base.copy(level = 48), at + 60_000))
        assertTrue(Battery.shouldReport(base, at, base.copy(charging = true), at + 60_000))
        assertTrue(Battery.shouldReport(BatteryState(14, false), at, BatteryState(13, false), at + 60_000))
        assertTrue(Battery.shouldReport(base, at, base, at + 31 * 60_000))
    }

    @Test
    fun battery_labels() {
        assertEquals("📱 64%", Battery.label(BatteryState(64, false, updatedAt = minutesAgo(5)), "📱", now))
        assertEquals("⌚ 40%⚡", Battery.label(BatteryState(40, true, updatedAt = minutesAgo(5)), "⌚", now))
        assertEquals("📱 3% (2 hr ago)", Battery.label(BatteryState(3, false, updatedAt = minutesAgo(120)), "📱", now))
        val presence = Presence(
            phoneBattery = BatteryState(10, false, minutesAgo(1)),
            watchBattery = BatteryState(80, true, minutesAgo(1)),
        )
        assertEquals("📱 10% · ⌚ 80%⚡", batteryLine(presence, now))
        assertTrue(anyBatteryLow(presence))
        assertFalse(anyBatteryLow(presence.copy(phoneBattery = BatteryState(10, charging = true))))
        assertEquals(null, batteryLine(Presence(), now))
    }

    @Test
    fun quietHours_window() {
        val overnight = QuietHours(startMinute = 23 * 60, endMinute = 7 * 60)
        assertTrue(overnight.contains(23 * 60))
        assertTrue(overnight.contains(2 * 60))
        assertFalse(overnight.contains(7 * 60))
        assertFalse(overnight.contains(12 * 60))
        val afternoon = QuietHours(startMinute = 13 * 60, endMinute = 14 * 60)
        assertTrue(afternoon.contains(13 * 60 + 30))
        assertFalse(afternoon.contains(14 * 60))
        assertFalse(QuietHours(startMinute = 600, endMinute = 600).contains(600))
    }

    @Test
    fun isQuiet_rules() {
        // `now` is 08:00 UTC; Tokyo is 17:00, Los Angeles is 00:00.
        val quiet = QuietHours()
        assertFalse(isQuiet(quiet, "Asia/Tokyo", now, asleep = false, online = false))
        assertTrue(isQuiet(quiet, "America/Los_Angeles", now, asleep = false, online = false))
        // Online overrides quiet hours (default on)…
        assertFalse(isQuiet(quiet, "America/Los_Angeles", now, asleep = false, online = true))
        // …unless the person turned that off.
        assertTrue(isQuiet(quiet.copy(allowWhenOnline = false), "America/Los_Angeles", now, asleep = false, online = true))
        // Detected sleep is quiet even outside the hours.
        assertTrue(isQuiet(quiet, "Asia/Tokyo", now, asleep = true, online = false))
        assertFalse(isQuiet(quiet.copy(enabled = false), "America/Los_Angeles", now, asleep = true, online = false))
        assertFalse(isQuiet(null, "America/Los_Angeles", now, asleep = true, online = false))
    }

    @Test
    fun countdown_text() {
        val today = java.time.LocalDate.of(2027, 1, 15)
        assertEquals("Together again in 30 days 💞", countdownText(today.plusDays(30), today))
        assertEquals("Together tomorrow 💞", countdownText(today.plusDays(1), today))
        assertEquals("Together today! 💞", countdownText(today, today))
        assertEquals(null, countdownText(today.minusDays(1), today))
    }

    @Test
    fun checkIn_visibility() {
        val checkIn = CheckIn(CheckIns.HOME, minutesAgo(30))
        assertTrue(checkIn.isVisible(now))
        assertFalse(checkIn.copy(timestamp = minutesAgo(4 * 60)).isVisible(now))
    }

    @Test
    fun durationTextIsCompact() {
        assertEquals("<1m", durationText(30_000))
        assertEquals("45m", durationText(45 * 60_000L))
        assertEquals("2h", durationText(120 * 60_000L))
        assertEquals("2h 14m", durationText(134 * 60_000L))
        assertEquals("3d 5h", durationText((3 * 24 + 5) * 3_600_000L))
    }

    @Test
    fun noConnectionShowsHowLong() {
        val now = 10_000_000_000L
        val presence = Presence(connected = false, lastSeen = now - 134 * 60_000L)
        val beat = Reading(70, now - 200 * 60_000L, Sources.WATCH)
        assertEquals("no connection · 2h 14m", statusLabel(PartnerStatus.NoConnection, presence, beat, now))
        assertEquals("away", statusLabel(PartnerStatus.Away, presence, beat, now))
    }

    @Test
    fun buzzPlaybackRoundTrips() {
        val playback = BuzzPlayback(72, 12, HapticStrength.Gentle)
        assertEquals(playback, BuzzPlayback.decode(playback.encode()))
        // Older phones send just the bpm.
        assertEquals(BuzzPlayback(65), BuzzPlayback.decode("65"))
        assertEquals(null, BuzzPlayback.decode("nonsense"))
    }

    @Test
    fun strengthScalesAmplitudes() {
        val (_, full) = Vibration.heartbeatWaveform(60, beats = 1, strength = 1f)
        val (_, gentle) = Vibration.heartbeatWaveform(60, beats = 1, strength = 0.4f)
        assertEquals(255, full.max())
        assertEquals(102, gentle.max())
        assertEquals(0, gentle.min())
    }

    @Test
    fun watchReport_roundTrips() {
        val full = WatchReport(bpm = 72, bpmAt = 1_000L, battery = 40, charging = true, asleep = false, asleepAt = 2_000L)
        assertEquals(full, WatchReport.decode(full.encode()))
        assertEquals(WatchReport(), WatchReport.decode(WatchReport().encode()))
        // Half-written or nonsense fields are ignored, not misread.
        assertEquals(WatchReport(battery = 50), WatchReport.decode("bpm=70;battery=50,false;asleep=true;x".toByteArray()))
    }

    @Test
    fun watchReport_carriesWatchId() {
        val report = WatchReport(bpm = 60, bpmAt = 5L, uid = "Abc123XYZ")
        assertEquals(report, WatchReport.decode(report.encode()))
        // Anything that isn't a plain user ID is dropped.
        assertEquals(null, WatchReport.decode("uid=a/b".toByteArray()).uid)
        assertEquals(null, WatchReport.decode("uid=".toByteArray()).uid)
    }
}
