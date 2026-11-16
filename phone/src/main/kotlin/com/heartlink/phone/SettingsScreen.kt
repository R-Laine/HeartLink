package com.heartlink.phone

import android.Manifest
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.heartlink.phone.health.HealthConnectSync
import com.heartlink.phone.location.HomeArrival
import com.heartlink.phone.sleep.NightJournal
import com.heartlink.phone.sleep.SleepApi
import com.heartlink.shared.SleepLearning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.heartlink.phone.service.HeartLinkService
import com.heartlink.phone.service.Settings
import com.heartlink.shared.HeartLinkColors
import com.heartlink.shared.HapticStrength
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.QuietHours
import com.heartlink.shared.Session
import com.heartlink.shared.Vibration
import com.heartlink.shared.minuteOfDayText
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(session: Session, partnerName: String?, onSendTestBeat: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val partner = partnerName ?: "your partner"

    var background by remember { mutableStateOf(Settings.runInBackground(context)) }
    var rich by remember { mutableStateOf(Settings.richNotification(context)) }
    var wakeSleep by remember { mutableStateOf(Settings.wakeSleepNotifications(context)) }
    var quiet by remember { mutableStateOf(Settings.quietHours(context)) }
    var homeSet by remember { mutableStateOf(HomeArrival.isEnabled(context)) }
    var homeNote by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) } // "start", "end", "date"
    var explainBackground by remember { mutableStateOf(false) }
    var strength by remember { mutableStateOf(Settings.hapticStrength(context)) }
    var beats by remember { mutableStateOf(Settings.hapticBeats(context)) }
    var replay by remember { mutableStateOf(Settings.replayAfterQuiet(context)) }
    val countdown by remember(session) { HeartRepository.observeCountdown(session.coupleId) }
        .collectAsStateWithLifecycle(initialValue = null)

    fun saveQuiet(new: QuietHours) {
        quiet = new
        Settings.setQuietHours(context, new)
        // Lets the service play held-back heartbeats straight away if it's no longer quiet.
        HeartLinkService.start(context, HeartLinkService.ACTION_QUIET_CHANGED)
        scope.launch {
            runCatching { HeartRepository.reportQuietHours(session, new) }
                .onFailure { Log.w("SettingsScreen", "Couldn't share quiet hours", it) }
        }
    }

    fun setHomeNow() {
        scope.launch {
            homeNote = "Finding your location…"
            val ok = runCatching { HomeArrival.setHomeToCurrentLocation(context) }.getOrDefault(false)
            homeSet = ok
            homeNote = when {
                !ok -> "Couldn't get your location. Try again with location turned on."
                !HomeArrival.hasBackgroundPermission(context) ->
                    "Saved, but HeartLink needs \"Allow all the time\" location access to notice when you get home."
                else -> "Home saved. $partner will be told when you get home."
            }
        }
    }

    var healthAvailability by remember { mutableStateOf(HealthConnectSync.availability(context)) }
    var healthSleep by remember { mutableStateOf(false) }
    var sleepApi by remember { mutableStateOf(SleepApi.hasPermission(context)) }
    var sleepRefresh by remember { mutableIntStateOf(0) }
    var learning by remember { mutableStateOf<NightJournal.Summary?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { sleepRefresh++ }
    LaunchedEffect(sleepRefresh) {
        healthAvailability = HealthConnectSync.availability(context)
        healthSleep = runCatching { HealthConnectSync.hasSleepPermission(context) }.getOrDefault(false)
        sleepApi = SleepApi.hasPermission(context)
        learning = withContext(Dispatchers.Default) { runCatching { NightJournal.summary(context) }.getOrNull() }
    }
    val healthSleepLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        sleepRefresh++
        HeartLinkService.start(context, HeartLinkService.ACTION_SLEEP_SOURCES_CHANGED)
    }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        sleepRefresh++
        HeartLinkService.start(context, HeartLinkService.ACTION_SLEEP_SOURCES_CHANGED)
    }

    val backgroundLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        setHomeNow()
    }
    val foregroundLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (HomeArrival.hasBackgroundPermission(context)) setHomeNow() else explainBackground = true
        } else {
            homeNote = "Location access is needed to notice when you get home."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Color.LightGray) }
            Spacer(Modifier.width(8.dp))
            Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Section("Together again")
        val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Next time you'll see each other")
                Detail(countdown?.let { dateFormat.format(it) } ?: "Not set. Shows a countdown for both of you.")
            }
            TextButton(onClick = { editing = "date" }) { Text(if (countdown == null) "Set" else "Change") }
            if (countdown != null) {
                TextButton(onClick = { scope.launch { runCatching { HeartRepository.setCountdown(session, null) } } }) {
                    Text("Clear")
                }
            }
        }

        Section("Heartbeats you receive")
        Label("Strength")
        Choices(HapticStrength.entries, strength, { it.label }) {
            strength = it
            Settings.setHapticStrength(context, it)
        }
        Label("Length")
        Choices(Vibration.BEAT_CHOICES, beats, { "$it beats" }) {
            beats = it
            Settings.setHapticBeats(context, it)
        }
        Detail("Applies to this phone and its watch. Some watches and phones can't vary their strength.")
        TextButton(onClick = { HeartLinkService.start(context, HeartLinkService.ACTION_TRY_HAPTICS) }, enabled = background) {
            Text("Try it")
        }

        Section("Quiet hours")
        Toggle(
            title = "Quiet hours",
            detail = "Heartbeats and messages arrive silently, with no vibration or sound. Also applies while your watch says you're asleep.",
            checked = quiet.enabled,
        ) { saveQuiet(quiet.copy(enabled = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("From", Modifier.weight(1f))
            TextButton(onClick = { editing = "start" }, enabled = quiet.enabled) { Text(minuteOfDayText(quiet.startMinute)) }
            Label("to")
            TextButton(onClick = { editing = "end" }, enabled = quiet.enabled) { Text(minuteOfDayText(quiet.endMinute)) }
        }
        Toggle(
            title = "Not while I'm using my phone",
            detail = "If you're online (using your phone), things come through normally even during quiet hours.",
            checked = quiet.allowWhenOnline,
            enabled = quiet.enabled,
        ) { saveQuiet(quiet.copy(allowWhenOnline = it)) }
        Toggle(
            title = "Play heartbeats when quiet hours end",
            detail = "Heartbeats sent during quiet hours vibrate once quiet hours are over, or when you start using your phone.",
            checked = replay,
            enabled = quiet.enabled,
        ) {
            replay = it
            Settings.setReplayAfterQuiet(context, it)
            if (!it) Settings.clearPendingReplay(context)
        }

        Section("Moments")
        Toggle(
            title = "When $partner wakes up or falls asleep",
            detail = "A gentle notification, from their watch's sleep detection.",
            checked = wakeSleep,
        ) {
            wakeSleep = it
            Settings.setWakeSleepNotifications(context, it)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Tell $partner when I get home")
                Detail(
                    homeNote ?: if (homeSet) {
                        "On. Your home location stays on this phone; only \"Home safe 🏠\" is shared."
                    } else {
                        "Stand at home and tap Set. Your location stays on this phone."
                    },
                )
            }
            TextButton(onClick = {
                when {
                    !HomeArrival.hasForegroundPermission(context) -> foregroundLocation.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                    !HomeArrival.hasBackgroundPermission(context) -> explainBackground = true
                    else -> setHomeNow()
                }
            }) { Text(if (homeSet) "Reset here" else "Set") }
            if (homeSet) {
                TextButton(onClick = {
                    HomeArrival.clear(context)
                    homeSet = false
                    homeNote = null
                }) { Text("Off") }
            }
        }

        Section("Sleep detection")
        Detail(
            "Your watch can be slow to notice you waking up, or miss it if it comes off to charge. " +
                "These help $partner see when you're really up.",
        )
        SourceRow(
            title = "Sleep from Health Connect",
            detail = when {
                healthAvailability == HealthConnectSync.Availability.Unsupported -> "Health Connect isn't available on this phone."
                healthSleep -> "On. Uses the sleep your watch's own app (such as Samsung Health) saves, to know when you woke up. It can arrive a while after you wake."
                else -> "Uses the sleep your watch's own app (such as Samsung Health) saves, to know when you woke up."
            },
            on = healthSleep,
            action = when (healthAvailability) {
                HealthConnectSync.Availability.Unsupported -> null
                HealthConnectSync.Availability.NeedsInstall -> "Set up"
                HealthConnectSync.Availability.Available -> "Allow"
            },
        ) {
            if (healthAvailability == HealthConnectSync.Availability.NeedsInstall) {
                runCatching { context.startActivity(HealthConnectSync.installIntent()) }
                    .onFailure { Log.w("SettingsScreen", "Play Store not available", it) }
            } else {
                healthSleepLauncher.launch(HealthConnectSync.sleepPermissionsToRequest(context))
            }
        }
        SourceRow(
            title = "Phone motion and light",
            detail = if (sleepApi) {
                "On. Android's sleep detection on this phone notices when you wake up, and when you go back to sleep after getting up in the night. It also notices when you're out and about, so you show as inactive rather than away."
            } else {
                "Android's sleep detection on this phone notices when you wake up, and when you go back to sleep after getting up in the night. It also notices when you're out and about, so you show as inactive rather than away. Needs physical activity access."
            },
            on = sleepApi,
            action = "Allow",
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Label("Learning your sleep")
                Detail(learningText(learning))
            }
            if ((learning?.nights ?: 0) > 0) {
                TextButton(onClick = {
                    NightJournal.clear(context)
                    sleepRefresh++
                }) { Text("Reset") }
            }
        }

        Section("Background")
        Toggle(
            title = "Stay connected in the background",
            detail = "Needed for status, messages, heartbeats, quiet hours and live watch readings while HeartLink is closed. Shows an always-on notification.",
            checked = background,
        ) {
            background = it
            Settings.setRunInBackground(context, it)
            if (it) HeartLinkService.start(context) else HeartLinkService.stop(context)
        }
        Toggle(
            title = "Show $partner in the notification",
            detail = "Their heartbeat, status, local time and battery. Off shows just \"Connected\".",
            checked = rich,
            enabled = background,
        ) {
            rich = it
            Settings.setRichNotification(context, it)
            HeartLinkService.start(context, HeartLinkService.ACTION_REFRESH)
        }
        TextButton(onClick = {
            // Some phones don't have this screen; fall back to the app's own settings.
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.recoverCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", context.packageName, null)),
                )
            }.onFailure { Log.w("SettingsScreen", "No battery settings screen", it) }
        }) { Text("Battery settings (if HeartLink keeps disconnecting)") }

        HorizontalDivider(color = Color.DarkGray)
        TextButton(onClick = onSendTestBeat) { Text("Send a test beat") }
        Spacer(Modifier.height(24.dp))
    }

    when (editing) {
        "start", "end" -> {
            val initial = if (editing == "start") quiet.startMinute else quiet.endMinute
            val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60)
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text(if (editing == "start") "Quiet from" else "Quiet until") },
                text = { TimePicker(state = state) },
                confirmButton = {
                    TextButton(onClick = {
                        val minute = state.hour * 60 + state.minute
                        saveQuiet(if (editing == "start") quiet.copy(startMinute = minute) else quiet.copy(endMinute = minute))
                        editing = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            )
        }

        "date" -> {
            val today = remember { LocalDate.now() }
            val state = rememberDatePickerState(
                initialSelectedDateMillis = (countdown ?: today.plusDays(7)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
                },
            )
            DatePickerDialog(
                onDismissRequest = { editing = null },
                confirmButton = {
                    TextButton(onClick = {
                        val millis = state.selectedDateMillis
                        if (millis != null) {
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            scope.launch {
                                runCatching { HeartRepository.setCountdown(session, date) }
                                    .onFailure { Log.w("SettingsScreen", "Couldn't set countdown", it) }
                            }
                        }
                        editing = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            ) { DatePicker(state = state) }
        }
    }

    if (explainBackground) {
        AlertDialog(
            onDismissRequest = { explainBackground = false },
            title = { Text("Allow location all the time") },
            text = {
                Text(
                    "To notice when you get home while HeartLink is closed, Android needs \"Allow all the time\" " +
                        "location access. On the next screen, choose that option. Your location stays on this phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainBackground = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        setHomeNow()
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explainBackground = false }) { Text("Not now") } },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

private fun learningText(summary: NightJournal.Summary?): String {
    fun minutes(ms: Long?) = "${(ms ?: 0L) / 60_000} min"
    return when {
        summary == null || summary.nights == 0 ->
            "Once Health Connect or Android's sleep detection records a night, HeartLink checks when it guessed you woke " +
                "against it, and adjusts to you. Everything stays on this phone."
        summary.nights < SleepLearning.MIN_NIGHTS ->
            "Checked ${summary.nights} of ${SleepLearning.MIN_NIGHTS} nights before it starts adjusting to you. " +
                "Off by ${minutes(summary.averageError)} on average so far."
        else ->
            "Checked ${summary.nights} nights. Wake-up guesses are off by ${minutes(summary.averageError)} on average " +
                "(${minutes(summary.defaultError)} with the starting settings). Stays on this phone."
    }
}

/** A sleep source: "On" once allowed, otherwise a button ([action]) to turn it on, if possible. */
@Composable
private fun SourceRow(title: String, detail: String, on: Boolean, action: String?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Label(title)
            Detail(detail)
        }
        Spacer(Modifier.width(12.dp))
        when {
            on -> Text("On", color = HeartLinkColors.Pink, fontWeight = FontWeight.SemiBold)
            action != null -> TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(4.dp))
    Text(title.uppercase(), color = HeartLinkColors.Pink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 16.sp, modifier = modifier)
}

@Composable
private fun Detail(text: String) {
    Text(text, color = Color.Gray, fontSize = 13.sp)
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Label(title)
            Detail(detail)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
