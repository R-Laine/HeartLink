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
    fun heartbeatScale_swellsThenRests() {
        assertEquals(1f, heartbeatScale(0f), 0.0001f)
        assertEquals(1.3f, heartbeatScale(0.15f), 0.0001f)
        assertEquals(1f, heartbeatScale(0.4f), 0.0001f)
        assertEquals(1f, heartbeatScale(0.9f), 0.0001f)
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
    fun partnerStatus_rules() {
        val active = Presence(connected = true, lastActive = minutesAgo(1))
        assertEquals(PartnerStatus.Online, partnerStatus(active, null, now))
        assertEquals(PartnerStatus.Inactive, partnerStatus(active.copy(lastActive = minutesAgo(30)), null, now))
        assertEquals(PartnerStatus.Away, partnerStatus(active.copy(lastActive = minutesAgo(61)), null, now))
        assertEquals(PartnerStatus.NoConnection, partnerStatus(active.copy(connected = false), null, now))
        assertEquals(PartnerStatus.Asleep, partnerStatus(active.copy(asleep = true, asleepAt = minutesAgo(60)), null, now))
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
}
