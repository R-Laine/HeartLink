package com.heartlink.phone

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartlink.phone.health.HealthConnectRow
import com.heartlink.phone.health.HealthConnectSync
import com.heartlink.phone.wear.WatchNodes
import com.heartlink.shared.Identity
import com.heartlink.shared.WearPaths
import com.heartlink.shared.HeartLinkColors
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.SendResult
import com.heartlink.shared.LIVE_THRESHOLD_MS
import com.heartlink.shared.PartnerStatus
import com.heartlink.shared.PendingInvite
import com.heartlink.shared.Reading
import com.heartlink.shared.Session
import com.heartlink.shared.Sources
import com.heartlink.shared.StatusRules
import com.heartlink.shared.ageText
import com.heartlink.shared.BatteryState
import com.heartlink.shared.Presence
import com.heartlink.shared.durationText
import com.heartlink.shared.beatPeriodMs
import com.heartlink.shared.countdownText
import com.heartlink.shared.heartbeatScale
import com.heartlink.shared.isQuiet
import com.heartlink.shared.isAsleep
import com.heartlink.shared.statusLabel
import com.heartlink.shared.localTimeText
import com.heartlink.shared.partnerStatus
import com.heartlink.shared.statusText
import com.heartlink.shared.timeDifferenceText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private val Background = Color(0xFF0E0E12)
private val CardColor = Color(0xFF1A1A22)
private val ChipColor = Color(0xFF26262F)
private const val DEFAULT_BUZZ_BPM = 70

private val HeartLinkDark = darkColorScheme(
    primary = HeartLinkColors.Pink,
    onPrimary = Color(0xFF2A0E16),
    secondary = HeartLinkColors.Blue,
    onSecondary = Color(0xFF06222E),
    secondaryContainer = ChipColor,
    onSecondaryContainer = Color.White,
    background = Background,
    surface = Background,
    surfaceContainer = CardColor,
    surfaceContainerHigh = CardColor,
    surfaceContainerHighest = ChipColor,
)

@Composable
fun PhoneApp(initialSession: Session?, onSessionChange: (Session?) -> Unit) {
    var session by remember { mutableStateOf(initialSession) }
    fun update(new: Session?) {
        HeartRepository.resetAccessDenied()
        session = new
        onSessionChange(new)
    }

    // If the database no longer accepts this phone's link (unlinked from another device, or a
    // link from an older Firebase project), go back to setup rather than showing nothing.
    val denied by HeartRepository.accessDenied.collectAsStateWithLifecycle()
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        HeartRepository.isStillLinked(current)
    }
    LaunchedEffect(denied) {
        val current = session
        if (!denied || current == null) return@LaunchedEffect
        // One refused read isn't proof: only unlink if the database confirms this phone is gone.
        // If it can't be checked (offline), keep checking, or the flag would never clear.
        while (true) {
            when (HeartRepository.isStillLinked(current)) {
                false -> return@LaunchedEffect update(null)
                true -> return@LaunchedEffect HeartRepository.resetAccessDenied()
                null -> delay(LINK_RECHECK_MS)
            }
        }
    }

    MaterialTheme(colorScheme = HeartLinkDark) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            val current = session
            if (current == null) {
                SetupFlow(onDone = ::update)
            } else {
                HeartsScreen(current, onUnlinked = { update(null) })
            }
        }
    }
}

@Composable
private fun HeartsScreen(session: Session, onUnlinked: () -> Unit) {
    val id = session.coupleId
    val context = LocalContext.current
    val members by remember(id) { HeartRepository.observeMembers(id) }
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val mine by remember(session) { HeartRepository.observeBeat(id, session.role) }
        .collectAsStateWithLifecycle(initialValue = null)
    val theirs by remember(session) { HeartRepository.observeBeat(id, session.partnerRole) }
        .collectAsStateWithLifecycle(initialValue = null)
    val partnerPresence by remember(session) { HeartRepository.observePresence(id, session.partnerRole) }
        .collectAsStateWithLifecycle(initialValue = null)
    val serverOffset by remember { HeartRepository.serverTimeOffset() }.collectAsStateWithLifecycle(initialValue = 0L)
    val countdown by remember(id) { HeartRepository.observeCountdown(id) }.collectAsStateWithLifecycle(initialValue = null)
    var deviceNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val now = deviceNow + serverOffset
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showUnlink by remember { mutableStateOf(false) }
    var showAddDevice by remember { mutableStateOf(false) }
    var showMessage by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    fun note(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    // While this screen is visible, tell the partner's devices we're looking, so their watch
    // switches to live readings.
    LifecycleResumeEffect(session) {
        val job = scope.launch {
            while (true) {
                runCatching { HeartRepository.markWatching(session) }
                    .onFailure { Log.w("HeartsScreen", "markWatching failed", it) }
                delay(30_000)
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            deviceNow = System.currentTimeMillis()
        }
    }

    val myName = members[session.role]
    val partnerName = members[session.partnerRole]

    if (showSettings) {
        SettingsScreen(
            session = session,
            partnerName = partnerName,
            onSendTestBeat = {
                scope.launch {
                    HeartRepository.upload(session, Random.nextInt(62, 96), force = true, source = Sources.TEST)
                }
            },
            onBack = { showSettings = false },
        )
        return
    }

    // Will things we send arrive silently right now?
    val partnerStatusNow = partnerStatus(partnerPresence, theirs, now)
    val partnerQuiet = partnerPresence?.let { p ->
        isQuiet(
            quiet = p.quietHours,
            timeZone = p.timeZone,
            now = now,
            asleep = p.isAsleep(now),
            online = partnerStatusNow == PartnerStatus.Online,
        )
    } == true

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "HeartLink",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.LightGray)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.LightGray)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Add device") }, onClick = {
                            showMenu = false
                            showAddDevice = true
                        })
                        DropdownMenuItem(text = { Text("Unlink this phone") }, onClick = {
                            showMenu = false
                            showUnlink = true
                        })
                    }
                }
            }

            countdown?.let { date -> countdownText(date, LocalDate.now()) }?.let { text ->
                Pill(text, color = HeartLinkColors.Pink, modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            if (partnerName != null) {
                PartnerCard(
                    name = partnerName,
                    reading = theirs,
                    color = HeartLinkColors.forPerson(session.partnerRole),
                    now = now,
                    status = partnerStatusNow,
                    statusText = statusLabel(partnerStatusNow, partnerPresence, theirs, now),
                    presence = partnerPresence,
                    notes = listOfNotNull(
                        partnerPresence?.checkIn?.takeIf { it.isVisible(now) }
                            ?.let { "${it.text} · ${ageText(now - it.timestamp)}" },
                        if (partnerQuiet) "🌙 Quiet hours: things you send arrive silently" else null,
                    ),
                    modifier = Modifier.weight(1f),
                )
            } else {
                InviteCard(session, myName, now, Modifier.weight(1f))
            }

            SharingStatus(session = session, reading = mine, now = now, onNote = ::note)

            if (partnerName != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val bpm = mine?.bpm ?: DEFAULT_BUZZ_BPM
                                when (HeartRepository.trySend { HeartRepository.sendBuzz(session, bpm, myName) }) {
                                    SendResult.Sent -> note(
                                        if (partnerQuiet) {
                                            "Sent quietly. $partnerName will see it when they check"
                                        } else {
                                            "Sent your heartbeat ($bpm bpm) to $partnerName"
                                        },
                                    )
                                    SendResult.Queued -> note("No connection yet. It'll send when you're back online")
                                    SendResult.Failed -> note("Couldn't send. Check your connection.")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HeartLinkColors.forPerson(session.partnerRole),
                            contentColor = Color(0xFF14141A),
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_heart), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Send heartbeat", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { showMessage = true },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Message", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
                HealthConnectRow(session)
            }
        }
    }

    if (showMessage && partnerName != null) {
        MessageDialog(
            partnerName = partnerName,
            onSend = { text ->
                scope.launch {
                    when (HeartRepository.trySend { HeartRepository.sendMessage(session, text, myName) }) {
                        SendResult.Sent -> note("Message sent")
                        SendResult.Queued -> note("No connection yet. It'll send when you're back online")
                        SendResult.Failed -> note("Couldn't send. Check your connection.")
                    }
                }
                showMessage = false
            },
            onDismiss = { showMessage = false },
        )
    }

    if (showAddDevice) {
        AddDeviceDialog(session, members, onDismiss = { showAddDevice = false })
    }

    if (showUnlink) {
        AlertDialog(
            onDismissRequest = { showUnlink = false },
            title = { Text("Unlink this phone?") },
            text = {
                Text(
                    "This phone and the watch it set up will stop sharing and showing heartbeats. " +
                        "To link again, start a new link, or use Add device on another of your phones.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnlink = false
                    scope.launch {
                        note("Unlinking…")
                        // Removes this phone and its watches in one write. Offline it would wait
                        // forever, so give up instead and stay linked rather than half-unlinked.
                        val watches = Identity.watches(context)
                        when (HeartRepository.trySend { HeartRepository.leave(session, watches) }) {
                            SendResult.Sent -> {
                                // Already removed from the couple; this just tells the watch now.
                                WatchNodes.send(context, WearPaths.UNLINK)
                                onUnlinked()
                            }
                            SendResult.Queued, SendResult.Failed ->
                                note("Couldn't unlink. Check your connection and try again.")
                        }
                    }
                }) { Text("Unlink") }
            },
            dismissButton = {
                TextButton(onClick = { showUnlink = false }) { Text("Cancel") }
            },
        )
    }
}

/** Shown until the partner joins. The invite code disappears for good once it's used. */
@Composable
private fun InviteCard(session: Session, myName: String?, now: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // null until the first value arrives, so we don't flash "expired" while loading.
    val pending by remember(session) { HeartRepository.observePendingInvite(session.coupleId).map { Loaded(it) } }
        .collectAsStateWithLifecycle(initialValue = null)
    var busy by remember { mutableStateOf(false) }

    fun newCode(old: PendingInvite?) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { HeartRepository.renewPartnerInvite(session, myName, old?.code) }
                .onFailure { Log.w("InviteCard", "Couldn't renew invite", it) }
            busy = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Waiting for your partner", color = Color.LightGray, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            val loaded = pending
            val invite = loaded?.value
            when {
                busy || loaded == null -> CircularProgressIndicator(color = Color.White)

                invite == null || invite.isExpired(now) -> {
                    Text("Your invite code has expired", color = Color.Gray, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    BigButton("Get a new invite code", HeartLinkColors.Blue) { newCode(invite) }
                }

                else -> {
                    Text("Send them this one-time code", color = Color.Gray, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    CodeText(invite.code)
                    Text(expiresText(invite.expiresAt - now), color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    BigButton("Share invite", HeartLinkColors.Blue) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Join me on HeartLink 💓 " +
                                    "Install the app and enter my invite code: ${invite.code}",
                            )
                        }
                        context.startActivity(Intent.createChooser(send, "Share invite"))
                    }
                }
            }
        }
    }
}

/**
 * One-time, 15-minute code for linking another of this person's phones. Only for their own seat:
 * the database refuses codes for the partner's seat, so nobody can add a device as the other.
 */
@Composable
private fun AddDeviceDialog(session: Session, members: Map<String, String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var forName by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun create(role: String) {
        val seatName = members[role] ?: return
        busy = true
        failed = false
        scope.launch {
            try {
                code = HeartRepository.createDeviceInvite(session, role, seatName, members[session.role])
                forName = seatName
            } catch (e: Exception) {
                Log.w("AddDeviceDialog", "Couldn't create device invite", e)
                failed = true
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a device") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val current = code
                when {
                    busy -> CircularProgressIndicator()
                    current != null -> {
                        Text("On the new device, tap \"I have an invite code\" and enter:", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        CodeText(current, color = Color.Unspecified)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Links a device as $forName. Works once, for 15 minutes.",
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                        )
                    }
                    else -> {
                        Text(
                            "Get a one-time code to link another of your phones, for example after reinstalling. " +
                                "Watches link automatically through their phone, so they don't need a code.",
                        )
                        if (failed) {
                            Spacer(Modifier.height(8.dp))
                            Text("Couldn't create a code. Try again.", color = HeartLinkColors.Pink)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (code == null && !busy) {
                TextButton(onClick = { create(session.role) }, enabled = members[session.role] != null) { Text("Get a code") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (code == null) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CodeText(code: String, color: Color = Color.White) {
    Text(
        code,
        color = color,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 6.sp,
    )
}

private class Loaded<T>(val value: T)

private fun expiresText(remainingMs: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMs)
    return when {
        hours >= 48 -> "Expires in ${hours / 24} days"
        hours >= 1 -> "Expires in $hours h"
        else -> "Expires in ${TimeUnit.MILLISECONDS.toMinutes(remainingMs).coerceAtLeast(1)} min"
    }
}

/** The partner, shown large: status, where they are, and their heart filling the space. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartnerCard(
    name: String,
    reading: Reading?,
    color: Color,
    now: Long,
    status: PartnerStatus,
    statusText: String,
    presence: Presence?,
    notes: List<String>,
    modifier: Modifier = Modifier,
) {
    val age = reading?.let { now - it.timestamp }
    val live = age != null && age < LIVE_THRESHOLD_MS
    val disconnected = status == PartnerStatus.NoConnection

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                name,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (status != PartnerStatus.Unknown) {
                    val tint = statusColor(status)
                    InfoChip(statusText, textColor = tint, background = tint.copy(alpha = 0.15f)) {
                        Box(Modifier.size(8.dp).background(tint, CircleShape))
                    }
                }
                localTimeText(presence?.timeZone, now)?.let { time ->
                    InfoChip(time, secondary = timeDifferenceText(presence?.timeZone, now)) {
                        ChipIcon(R.drawable.ic_schedule)
                    }
                }
                // With no connection the status chip already says how long; don't repeat it.
                presence?.phoneBattery?.let { BatteryChip(R.drawable.ic_smartphone, it, now, showAge = !disconnected) }
                // Their watch status is unknown too when their phone can't be reached.
                if (!disconnected) WatchChip(presence, now)
            }
            if (disconnected) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Can't reach $name's phone or watch",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // The heart takes whatever room is left.
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val size = min(min(maxWidth, maxHeight) * 0.9f, 240.dp)
                BeatingHeart(bpm = if (live && !disconnected) reading?.bpm else null, color = color, size = size, dim = reading == null || disconnected)
            }

            if (reading != null) {
                if (disconnected) Text("Last known", color = Color.Gray, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(reading.bpm.toString(), color = if (disconnected) Color.LightGray else Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("bpm", color = Color.Gray, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text(
                    statusText(age ?: 0, reading.source),
                    color = if (live) color else Color.Gray,
                    fontSize = 14.sp,
                )
            } else {
                Text("No heartbeat yet", color = Color.LightGray, fontSize = 18.sp)
                Text(
                    "It appears once $name's watch or Health Connect shares one",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }

            notes.forEach {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color.LightGray, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Whether your own heartbeat is reaching your partner, without the numbers. When it isn't,
 * says why as best it can tell and what to do about it.
 */
@Composable
private fun SharingStatus(session: Session, reading: Reading?, now: Long, onNote: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Spins from a tap until a new reading arrives (or we give up waiting).
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(reading?.timestamp) { refreshing = false }
    // null until checked, so we don't flash "watch not connected" on open.
    var watchConnected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            watchConnected = runCatching { WatchNodes.reachable(context).isNotEmpty() }.getOrDefault(false)
            delay(30_000)
        }
    }

    val age = reading?.let { now - it.timestamp }
    val fromHealth = reading?.source == Sources.HEALTH_CONNECT
    val staleAfter = if (fromHealth) HEALTH_STALE_MS else WATCH_STALE_MS
    val healthy = age != null && age < staleAfter

    val (title, detail) = when {
        healthy -> "Sharing your heartbeat" to when {
            fromHealth -> "From Health Connect · updated ${ageText(age!!)}"
            age!! < LIVE_THRESHOLD_MS -> "Live from your watch"
            else -> "From your watch · updated ${ageText(age)}"
        }

        fromHealth -> "Your heartbeat hasn't updated in ${ageText(age!!).removeSuffix(" ago")}" to
            "Open your watch's or fitness tracker's own app so it syncs to Health Connect."

        watchConnected == false -> (if (reading == null) "Not sharing your heartbeat yet" else "Your heartbeat hasn't updated in ${ageText(age!!).removeSuffix(" ago")}") to
            "No HeartLink watch is connected to this phone. Check your watch is on, charged and connected over Bluetooth. " +
            "No HeartLink watch? Share from Health Connect below."

        reading == null -> "Not sharing your heartbeat yet" to
            "Open HeartLink on your watch and allow heart rate access. It links itself through this phone."

        else -> "Your heartbeat hasn't updated in ${ageText(age!!).removeSuffix(" ago")}" to
            "Open HeartLink on your watch. If it asks, allow heart rate and physical activity access, " +
            "and check the watch isn't in battery saver."
    }
    val dot = if (healthy) Color(0xFF4CD964) else Color(0xFFFFB020)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (healthy) CardColor else Color(0xFF2A2418)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = if (healthy) Alignment.CenterVertically else Alignment.Top,
        ) {
            Box(Modifier.padding(top = if (healthy) 0.dp else 6.dp).size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(detail, color = if (healthy) Color.Gray else Color(0xFFE6D3A8), fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.LightGray)
                } else {
                    IconButton(
                        onClick = {
                            refreshing = true
                            scope.launch {
                                val waiting = refreshMyHeartbeat(context, session, preferHealth = fromHealth, onNote)
                                if (waiting) delay(REFRESH_WAIT_MS)
                                refreshing = false
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Update now", tint = Color.LightGray)
                    }
                }
            }
        }
    }
}

/**
 * Asks for a fresh reading of this person's heartbeat: the watch measures live for a short
 * while, or (with no HeartLink watch, or when sharing from Health Connect) the newest Health
 * Connect sample is uploaded. Returns true if a reading should arrive shortly.
 */
private suspend fun refreshMyHeartbeat(
    context: Context,
    session: Session,
    preferHealth: Boolean,
    onNote: (String) -> Unit,
): Boolean {
    if (!preferHealth && WatchNodes.send(context, WearPaths.LIVE_REQUEST)) {
        onNote("Asked your watch to measure now")
        return true
    }
    val shared = runCatching { HealthConnectSync.syncOnce(context, session) }
    when {
        shared.getOrDefault(false) -> onNote("Updated from Health Connect")
        shared.isSuccess && runCatching { HealthConnectSync.hasPermission(context) }.getOrDefault(false) ->
            onNote("No newer reading in Health Connect")
        else -> onNote("Couldn't reach your watch. Check it's on and connected over Bluetooth.")
    }
    return false
}

private val WATCH_STALE_MS = TimeUnit.MINUTES.toMillis(30)
private val REFRESH_WAIT_MS = TimeUnit.SECONDS.toMillis(60)
private val LINK_RECHECK_MS = TimeUnit.SECONDS.toMillis(30)
private val HEALTH_STALE_MS = TimeUnit.HOURS.toMillis(3)

@Composable
private fun BeatingHeart(bpm: Int?, color: Color, size: Dp, dim: Boolean = false, glow: Boolean = true) {
    // A still heart doesn't need a frame every 16 ms: only animate while there's a beat to show.
    val scale = if (bpm != null) beatScale(bpm) else 1f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        if (glow && bpm != null) {
            Box(
                Modifier
                    .size(size)
                    .scale(scale * 1.1f)
                    .background(Brush.radialGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent)), CircleShape),
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_heart),
            contentDescription = null,
            // A plain grey when dimmed: a faded pink just looks muddy on the dark card.
            tint = if (dim) Color(0xFF3A3A46) else color,
            modifier = Modifier
                .size(size * 0.72f)
                .scale(scale),
        )
    }
}

/** One chip style for everything in the partner card's header: same height, icon, text. */
@Composable
private fun InfoChip(
    text: String,
    textColor: Color = Color.LightGray,
    background: Color = ChipColor,
    secondary: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    leading: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .height(32.dp)
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 12.dp),
    ) {
        leading()
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        trailing?.invoke()
        secondary?.let { Text(it, color = Color.Gray, fontSize = 13.sp, maxLines = 1) }
    }
}

@Composable
private fun ChipIcon(res: Int, tint: Color = Color.Gray) {
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
}

/**
 * The partner's watch: its battery while their phone can reach it, "not connected" (and for how
 * long) when it can't. Phones from before this was reported only show the battery.
 */
@Composable
private fun WatchChip(presence: Presence?, now: Long) {
    val link = presence?.watch
    val battery = presence?.watchBattery
    when {
        link == null -> battery?.let { BatteryChip(R.drawable.ic_watch, it, now) }
        link.connected && battery != null -> BatteryChip(R.drawable.ic_watch, battery, now)
        link.connected -> InfoChip("connected") { ChipIcon(R.drawable.ic_watch) }
        else -> InfoChip(
            "not connected",
            textColor = Color.Gray,
            secondary = link.since?.let { durationText(now - it) },
        ) { ChipIcon(R.drawable.ic_watch, tint = Color.DarkGray) }
    }
}

@Composable
private fun BatteryChip(icon: Int, battery: BatteryState, now: Long, showAge: Boolean = true) {
    val color = if (battery.isLow) HeartLinkColors.Pink else Color.LightGray
    // Only mention the age once it's old enough to matter.
    val age = battery.updatedAt?.let { now - it }?.takeIf { showAge && it > TimeUnit.HOURS.toMillis(1) }
    InfoChip(
        "${battery.level}%",
        textColor = color,
        secondary = age?.let { durationText(it) },
        trailing = if (battery.charging) {
            { Icon(painterResource(R.drawable.ic_bolt), contentDescription = "charging", tint = color, modifier = Modifier.size(14.dp)) }
        } else {
            null
        },
    ) { ChipIcon(icon, tint = if (battery.isLow) HeartLinkColors.Pink else Color.Gray) }
}

@Composable
private fun Pill(text: String, color: Color = Color.LightGray, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        modifier = modifier
            .background(ChipColor, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The heart's size right now, beating at [bpm]. */
@Composable
private fun beatScale(bpm: Int): Float {
    val transition = rememberInfiniteTransition(label = "beat")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(beatPeriodMs(bpm), easing = LinearEasing)),
        label = "progress",
    )
    return heartbeatScale(progress, beatPeriodMs(bpm))
}
