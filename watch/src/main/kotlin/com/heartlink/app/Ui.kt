package com.heartlink.app

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.heartlink.shared.HeartLinkColors
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.LIVE_THRESHOLD_MS
import com.heartlink.shared.Session
import com.heartlink.shared.statusText
import com.heartlink.shared.localTimeText
import com.heartlink.shared.partnerStatus
import com.heartlink.shared.statusLabel
import com.heartlink.shared.anyBatteryLow
import com.heartlink.shared.batteryLine
import com.heartlink.shared.countdownText
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import com.heartlink.shared.beatPeriodMs
import com.heartlink.shared.heartbeatScale
import kotlinx.coroutines.delay

@Composable
fun HeartLinkApp(session: Session?, myBpm: Int?, onLinked: (Session) -> Unit, onUnlink: () -> Unit) {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (session == null) WatchSetup(onDone = onLinked) else HeartScreen(session, myBpm, onUnlink)
        }
    }
}

@Composable
private fun HeartScreen(session: Session, myBpm: Int?, onUnlink: () -> Unit) {
    val code = session.coupleId
    val members by remember(code) { HeartRepository.observeMembers(code) }
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val theirs by remember(session) { HeartRepository.observeBeat(code, session.partnerRole) }
        .collectAsStateWithLifecycle(initialValue = null)
    val serverOffset by remember { HeartRepository.serverTimeOffset() }.collectAsStateWithLifecycle(initialValue = 0L)
    var deviceNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val partnerPresence by remember(session) { HeartRepository.observePresence(code, session.partnerRole) }
        .collectAsStateWithLifecycle(initialValue = null)
    val countdown by remember(code) { HeartRepository.observeCountdown(code) }.collectAsStateWithLifecycle(initialValue = null)
    var confirmUnlink by remember { mutableStateOf(false) }
    var sentNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            deviceNow = System.currentTimeMillis()
        }
    }

    val now = deviceNow + serverOffset
    val partnerName = members[session.partnerRole]
    val partnerAgeMs = theirs?.let { now - it.timestamp }
    val partnerLive = partnerAgeMs != null && partnerAgeMs < LIVE_THRESHOLD_MS

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        item {
            PersonRow(
                name = members[session.role] ?: "You",
                bpm = myBpm,
                color = HeartLinkColors.forPerson(session.role),
                status = if (myBpm == null) "measuring…" else "live",
                beating = myBpm != null,
            )
        }
        item {
            if (partnerName != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PersonRow(
                        name = partnerName,
                        bpm = theirs?.bpm,
                        color = HeartLinkColors.forPerson(session.partnerRole),
                        status = partnerAgeMs?.let { statusText(it, theirs?.source) } ?: "waiting…",
                        beating = partnerLive,
                    )
                    val presenceLine = listOfNotNull(
                        statusLabel(partnerStatus(partnerPresence, theirs, now), partnerPresence, theirs, now).ifEmpty { null },
                        localTimeText(partnerPresence?.timeZone, now),
                    ).joinToString(" · ")
                    if (presenceLine.isNotEmpty()) Text(presenceLine, color = Color.Gray, fontSize = 11.sp)
                    partnerPresence?.checkIn?.takeIf { it.isVisible(now) }?.let {
                        Text(it.text, color = Color.LightGray, fontSize = 11.sp)
                    }
                    batteryLine(partnerPresence, now)?.let {
                        Text(
                            it,
                            color = if (anyBatteryLow(partnerPresence)) HeartLinkColors.Pink else Color.Gray,
                            fontSize = 11.sp,
                        )
                    }
                }
            } else {
                Text(
                    "Waiting for your partner to join",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        countdown?.let { countdownText(it, java.time.LocalDate.now()) }?.let { text ->
            item { Text(text, color = HeartLinkColors.Pink, fontSize = 12.sp, textAlign = TextAlign.Center) }
        }
        if (partnerName != null) {
            item {
                Chip(
                    label = { Text(sentNote ?: "♥ Send heartbeat") },
                    onClick = {
                        scope.launch {
                            val bpm = myBpm ?: 70
                            runCatching { HeartRepository.sendBuzz(session, bpm, members[session.role]) }
                                .onSuccess { sentNote = "Sent ($bpm bpm)" }
                                .onFailure { sentNote = "Couldn't send" }
                        }
                    },
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = HeartLinkColors.forPerson(session.role),
                        contentColor = Color.Black,
                    ),
                )
            }
        }
        item {
            Chip(
                label = { Text(if (confirmUnlink) "Tap again to unlink" else "Unlink") },
                onClick = { if (confirmUnlink) onUnlink() else confirmUnlink = true },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
private fun PersonRow(name: String, bpm: Int?, color: Color, status: String, beating: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = Color.LightGray, fontSize = 13.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulsingHeart(bpm = if (beating) bpm else null, color = color)
            Text(bpm?.toString() ?: "--", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Text(status, color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
private fun PulsingHeart(bpm: Int?, color: Color) {
    val transition = rememberInfiniteTransition(label = "beat")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(beatPeriodMs(bpm), easing = LinearEasing)),
        label = "progress",
    )
    val scale = if (bpm != null) heartbeatScale(progress) else 1f
    Text("♥", color = color, fontSize = 28.sp, modifier = Modifier.scale(scale))
}
