package com.heartlink.app

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.heartlink.shared.Battery
import com.heartlink.shared.BatteryState
import com.heartlink.shared.HeartLinkColors
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.LIVE_THRESHOLD_MS
import com.heartlink.shared.PartnerStatus
import com.heartlink.shared.Presence
import com.heartlink.shared.Session
import com.heartlink.shared.StatusRules
import com.heartlink.shared.beatPeriodMs
import com.heartlink.shared.countdownText
import com.heartlink.shared.heartbeatScale
import com.heartlink.shared.isQuiet
import com.heartlink.shared.localTimeText
import com.heartlink.shared.partnerStatus
import com.heartlink.shared.statusLabel
import com.heartlink.shared.statusText
import com.heartlink.shared.timeDifferenceText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val DEFAULT_BUZZ_BPM = 70
private val DetailColor = Color(0xFFB0B0BA)
private val PanelColor = Color(0xFF1A1A22)

@Composable
fun HeartLinkApp(
    session: Session?,
    myBpm: Int?,
    setupNote: String?,
    onLinked: (Session) -> Unit,
    onUnlink: () -> Unit,
) {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (session == null) WatchSetup(note = setupNote, onDone = onLinked) else HeartScreen(session, myBpm, onUnlink)
        }
    }
}

/** Shown after a crash, so the error can be photographed and shared without a computer. */
@Composable
fun CrashReport(report: String, onDismiss: () -> Unit) {
    MaterialTheme {
        val listState = rememberScalingLazyListState()
        Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 28.dp),
            ) {
                item { Text("HeartLink closed unexpectedly", color = HeartLinkColors.Pink, fontSize = 14.sp, textAlign = TextAlign.Center) }
                item { Text("Take a screenshot of this to share it.", color = DetailColor, fontSize = 11.sp, textAlign = TextAlign.Center) }
                item { CompactChip(label = { Text("Continue") }, onClick = onDismiss, colors = ChipDefaults.secondaryChipColors()) }
                report.lines().filter { it.isNotBlank() }.take(40).forEach { line ->
                    item { Text(line.trim(), color = Color.White, fontSize = 9.sp, lineHeight = 11.sp) }
                }
                item { CompactChip(label = { Text("Continue") }, onClick = onDismiss, colors = ChipDefaults.secondaryChipColors()) }
            }
        }
    }
}

@Composable
private fun HeartScreen(session: Session, myBpm: Int?, onUnlink: () -> Unit) {
    val id = session.coupleId
    val members by remember(id) { HeartRepository.observeMembers(id) }
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val theirs by remember(session) { HeartRepository.observeBeat(id, session.partnerRole) }
        .collectAsStateWithLifecycle(initialValue = null)
    val partnerPresence by remember(session) { HeartRepository.observePresence(id, session.partnerRole) }
        .collectAsStateWithLifecycle(initialValue = null)
    val countdown by remember(id) { HeartRepository.observeCountdown(id) }.collectAsStateWithLifecycle(initialValue = null)
    val serverOffset by remember { HeartRepository.serverTimeOffset() }.collectAsStateWithLifecycle(initialValue = 0L)
    var deviceNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmUnlink by remember { mutableStateOf(false) }
    var sentNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)

    // While visible, let the partner's devices know we're looking (their watch goes live).
    LifecycleResumeEffect(session) {
        val job = scope.launch {
            while (true) {
                runCatching { HeartRepository.markWatching(session) }
                delay(30_000)
            }
        }
        onPauseOrDispose { job.cancel() }
    }
    LaunchedEffect(sentNote) {
        if (sentNote != null) {
            delay(3_000)
            sentNote = null
        }
    }
    LaunchedEffect(confirmUnlink) {
        if (confirmUnlink) {
            delay(4_000)
            confirmUnlink = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            deviceNow = System.currentTimeMillis()
        }
    }

    val now = deviceNow + serverOffset
    val myName = members[session.role]
    val partnerName = members[session.partnerRole]
    val partnerColor = HeartLinkColors.forPerson(session.partnerRole)
    val status = partnerStatus(partnerPresence, theirs, now)
    val disconnected = status == PartnerStatus.NoConnection
    val age = theirs?.let { now - it.timestamp }
    val live = age != null && age < LIVE_THRESHOLD_MS && !disconnected

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            autoCentering = AutoCenteringParams(itemIndex = 1),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (partnerName == null) {
                item { Spacer(Modifier.height(1.dp)) }
                item {
                    Text(
                        "Waiting for your partner to join.\nShare the invite code from your phone.",
                        color = DetailColor,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                // Name and status
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            partnerName,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (status != PartnerStatus.Unknown) {
                            val tint = HeartLinkColors.forStatus(status)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(tint, CircleShape))
                                Spacer(Modifier.width(5.dp))
                                Text(statusLabel(status, partnerPresence, theirs, now), color = tint, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Their heart
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BeatingHeart(
                                bpm = if (live) theirs?.bpm else null,
                                color = if (theirs == null || disconnected) Color(0xFF3A3A46) else partnerColor,
                                size = 40.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                theirs?.bpm?.toString() ?: "--",
                                color = if (disconnected) Color.LightGray else Color.White,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            when {
                                theirs == null -> "No heartbeat yet"
                                // The status line above already says how long; don't repeat it.
                                disconnected -> "Last known"
                                else -> statusText(age ?: 0, theirs?.source)
                            },
                            color = if (live) partnerColor else Color.Gray,
                            fontSize = 12.sp,
                        )
                    }
                }

                // Send heartbeat
                item {
                    Chip(
                        label = { Text(sentNote ?: "Send heartbeat", maxLines = 1) },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_heart),
                                contentDescription = null,
                                modifier = Modifier.size(ChipDefaults.IconSize),
                            )
                        },
                        onClick = {
                            scope.launch {
                                val bpm = myBpm ?: DEFAULT_BUZZ_BPM
                                runCatching { HeartRepository.sendBuzz(session, bpm, myName) }
                                    .onSuccess { sentNote = "Sent ♥" }
                                    .onFailure { sentNote = "Couldn't send" }
                            }
                        },
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = partnerColor,
                            contentColor = Color(0xFF14141A),
                            iconColor = Color(0xFF14141A),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Details: their time, devices, check-in, quiet hours
                item { PartnerDetails(partnerPresence, now, disconnected) }
            }

            countdown?.let { countdownText(it, LocalDate.now()) }?.let { text ->
                item { Text(text, color = HeartLinkColors.Pink, fontSize = 12.sp, textAlign = TextAlign.Center) }
            }

            // You: this watch is what measures your heart, so show it's working.
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    BeatingHeart(bpm = myBpm, color = HeartLinkColors.forPerson(session.role), size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (myBpm != null) "You · $myBpm bpm" else "You · measuring…",
                        color = Color.Gray,
                        fontSize = 12.sp,
                    )
                }
            }

            item {
                CompactChip(
                    label = { Text(if (confirmUnlink) "Tap again to unlink" else "Unlink") },
                    onClick = { if (confirmUnlink) onUnlink() else confirmUnlink = true },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

/** A small panel of the partner's details: local time, phone and watch, check-in, quiet hours. */
@Composable
private fun PartnerDetails(presence: Presence?, now: Long, disconnected: Boolean) {
    // With no connection the status line shows how long, so readings don't repeat "3 hr ago".
    fun ageless(battery: BatteryState) = if (disconnected) battery.updatedAt ?: now else now
    val rows = buildList<@Composable () -> Unit> {
        localTimeText(presence?.timeZone, now)?.let { time ->
            val diff = timeDifferenceText(presence?.timeZone, now)
            add { DetailRow(R.drawable.ic_schedule, listOfNotNull(time, diff).joinToString(" · ")) }
        }
        presence?.phoneBattery?.let { battery ->
            add { DetailRow(R.drawable.ic_smartphone, batteryText(battery, ageless(battery)), lowColor(battery)) }
        }
        val link = presence?.watch
        when {
            // Their watch status is unknown too when their phone can't be reached.
            disconnected -> Unit
            link != null && !link.connected -> add { DetailRow(R.drawable.ic_watch, "Not connected", Color.Gray) }
            presence?.watchBattery != null -> {
                val battery = presence.watchBattery!!
                add { DetailRow(R.drawable.ic_watch, batteryText(battery, ageless(battery)), lowColor(battery)) }
            }
            link != null -> add { DetailRow(R.drawable.ic_watch, "Connected") }
        }
        presence?.checkIn?.takeIf { it.isVisible(now) }?.let { checkIn ->
            add { Text(checkIn.text, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center) }
        }
        if (presence != null && partnerIsQuiet(presence, now)) {
            add { Text("Quiet hours: arrives silently", color = Color(0xFF8E8EF0), fontSize = 11.sp, textAlign = TextAlign.Center) }
        }
    }
    if (rows.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelColor, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { it() }
    }
}

@Composable
private fun DetailRow(@DrawableRes icon: Int, text: String, color: Color = DetailColor) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, maxLines = 1)
    }
}

private fun batteryText(battery: BatteryState, now: Long): String =
    Battery.label(battery, "", now).trim().replace("⚡", " charging")

private fun lowColor(battery: BatteryState): Color = if (battery.isLow) HeartLinkColors.Pink else DetailColor

private fun partnerIsQuiet(p: Presence, now: Long): Boolean {
    val asleepAt = p.asleepAt
    return isQuiet(
        quiet = p.quietHours,
        timeZone = p.timeZone,
        now = now,
        asleep = p.asleep && asleepAt != null && now - asleepAt < StatusRules.ASLEEP_STALE_MS,
        online = partnerStatus(p, null, now) == PartnerStatus.Online,
    )
}

@Composable
private fun BeatingHeart(bpm: Int?, color: Color, size: Dp) {
    val transition = rememberInfiniteTransition(label = "beat")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(beatPeriodMs(bpm), easing = LinearEasing)),
        label = "progress",
    )
    val scale = if (bpm != null) heartbeatScale(progress) else 1f
    Icon(
        painterResource(R.drawable.ic_heart),
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(size).scale(scale),
    )
}
