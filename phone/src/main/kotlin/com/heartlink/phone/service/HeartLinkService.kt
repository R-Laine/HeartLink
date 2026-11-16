package com.heartlink.phone.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.heartlink.phone.R
import com.heartlink.phone.wear.WatchNodes
import com.heartlink.shared.Battery
import com.heartlink.shared.Buzz
import com.heartlink.shared.Moments
import com.heartlink.shared.StatusRules
import com.heartlink.shared.isQuiet
import com.heartlink.shared.SleepState
import com.heartlink.phone.health.HealthConnectSync
import com.heartlink.phone.sleep.SleepApi
import com.heartlink.phone.sleep.NightJournal
import com.heartlink.phone.activity.ActivityLog
import com.heartlink.phone.activity.Motion
import com.heartlink.shared.ActivityKind
import com.heartlink.shared.MOVING_REPORT_MS
import android.media.AudioManager
import android.os.BatteryManager
import com.heartlink.shared.FinishedSleep
import com.heartlink.shared.SleepDecision
import com.heartlink.shared.SleepEvidence
import com.heartlink.shared.SleepModel
import com.heartlink.shared.isAsleep
import com.heartlink.shared.sleepDecision
import com.heartlink.shared.SendResult
import com.heartlink.shared.Devices
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import com.heartlink.shared.LIVE_THRESHOLD_MS
import com.heartlink.shared.Presence
import com.heartlink.shared.Reading
import com.heartlink.shared.Sources
import com.heartlink.shared.Session
import com.heartlink.shared.BuzzPlayback
import com.heartlink.shared.WearPaths
import com.heartlink.shared.localTimeText
import com.heartlink.shared.PartnerStatus
import com.heartlink.shared.partnerStatus
import com.heartlink.shared.statusLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Keeps HeartLink connected in the background, so it works without a server:
 *  - presence: marks this person connected (Firebase flips it off if the phone dies) and records
 *    screen-on/unlock as activity, which drives the partner's online/inactive/away/no connection status
 *  - delivers the partner's messages as notifications (Wear OS mirrors them to the watch)
 *  - plays the partner's heartbeat on the watch (or this phone) when they send it
 *  - asks the watch for live readings while the partner is looking
 *  - shows the always-on notification, optionally with the partner's heartbeat, status and time
 */
class HeartLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Session? = null
    private var jobs: Job? = null
    private var lastActiveWrite = 0L
    /** [lastActiveWrite] in server time, to tell this phone's lastActive from the watch app's. */
    private var lastActiveWriteServer = 0L
    private var myLastBpm: Int? = null
    private var myLastBeat: Reading? = null
    private var myName: String? = null
    private var myPresence: Presence? = null
    // Decides when this person is properly up while their watch still says asleep. Main thread only.
    // Sleep: this person's recent use, the last Health Connect sleep, and the verdict from them.
    private val activityLog by lazy { ActivityLog(this) }
    private var lastMovingWrite = 0L
    private var lastCallWrite = 0L
    private var healthSleeps: List<FinishedSleep> = emptyList()
    private var mySleep: SleepDecision? = null
    private val healthCheck = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var lastInteraction = 0L

    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            markActive()
            // The status notification stops ticking while the screen is off; catch it up.
            if (intent.action == Intent.ACTION_SCREEN_ON) clock.value = System.currentTimeMillis()
            // Picking up the phone can end quiet hours early ("not while I'm using my phone").
            scope.launch { replayIfDue() }
        }
    }

    // Plugging in or unplugging a cable: someone's hands were on the phone. Not a wireless pad,
    // where a phone shifting in the night can disconnect and reconnect by itself.
    private var wirelessCharging = false
    private val chargerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                wirelessCharging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_WIRELESS
            }
            if (!wirelessCharging) markEngaged(ActivityKind.Charger)
        }
    }

    // Battery changes arrive often (voltage, temperature); Battery.reportIfChanged filters them.
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = session ?: return
            val state = Battery.fromIntent(intent) ?: return
            scope.launch { Battery.reportIfChanged(this@HeartLinkService, current, Devices.PHONE, state) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        startInForeground(buildStatusNotification(null))
        ContextCompat.registerReceiver(
            this,
            activityReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            chargerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = Identity.get(this)
        if (current == null || !Settings.runInBackground(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SEND_HEARTBEAT -> sendMyHeartbeat(current)
            ACTION_REFRESH -> refreshNotificationNow()
            ACTION_QUIET_CHANGED -> {
                reportQuietHours(current)
                scope.launch { replayIfDue() }
            }
            ACTION_TRY_HAPTICS -> scope.launch { playHeartbeat(myLastBpm ?: DEFAULT_BPM) }
            ACTION_SLEEP_SOURCES_CHANGED -> {
                SleepApi.register(this)
                Motion.register(this)
                healthCheck.tryEmit(Unit)
            }
        }
        if (current != session) {
            session = current
            jobs?.cancel()
            jobs = scope.launch { runFor(current) }
        }
        markActive()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(activityReceiver)
        unregisterReceiver(chargerReceiver)
        unregisterReceiver(batteryReceiver)
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_STATUS, notification, type)
    }

    // ---- Work while linked ---------------------------------------------------------------

    private suspend fun runFor(session: Session) = kotlinx.coroutines.coroutineScope {
        val tz = ZoneId.systemDefault().id
        SleepApi.register(this@HeartLinkService)
        Motion.register(this@HeartLinkService)
        launch { Motion.changed.collect { noteMoving() } }
        // Sleep: decide again whenever the Sleep API has news...
        launch {
            SleepApi.changed.collect {
                checkNight()
                decideSleep()
            }
        }
        // ...and look in Health Connect every few minutes (it can't tell us), only while the
        // watch says asleep, since that's the only time it can change anything.
        launch {
            merge(
                healthCheck,
                flow {
                    while (true) {
                        emit(Unit)
                        delay(SLEEP_CHECK_MS)
                    }
                },
            ).collect {
                val now = HeartRepository.toServerTime(System.currentTimeMillis())
                // Also after waking, while last night is waiting to be checked.
                if (myPresence?.asleep != true && !NightJournal.awaitingTruth(this@HeartLinkService, now)) return@collect
                healthSleeps = runCatching { HealthConnectSync.recentSleeps(this@HeartLinkService) }
                    .onFailure { Log.i(TAG, "No Health Connect sleep", it) }
                    .getOrDefault(emptyList())
                checkNight()
                decideSleep()
            }
        }
        launch { Battery.reportIfChanged(this@HeartLinkService, session, Devices.PHONE) }
        // Each time the connection comes back, re-send quiet hours: a report that failed while
        // offline would otherwise wait until they were next changed.
        launch {
            HeartRepository.maintainPresence(session, tz).safe().collect { connected ->
                if (connected) reportQuietHours(session)
            }
        }
        launch {
            HeartRepository.observePresence(session.coupleId, session.role).safe().collect { presence ->
                val watchChanged = presence?.asleepAt != myPresence?.asleepAt || presence?.asleep != myPresence?.asleep
                myPresence = presence
                NightJournal.noteWatch(this@HeartLinkService, presence)
                // Using the watch app counts too; it shows up here as a lastActive this phone didn't write.
                presence?.lastActive?.let { at ->
                    if (at > lastActiveWriteServer + OWN_WRITE_SLACK_MS) activityLog.add(ActivityKind.WatchApp, at)
                }
                if (watchChanged) healthCheck.tryEmit(Unit)
                decideSleep()
            }
        }

        launch {
            HeartRepository.observeMembers(session.coupleId).safe().collect { myName = it[session.role] }
        }
        launch {
            HeartRepository.observeBeat(session.coupleId, session.role).safe().collect {
                myLastBpm = it?.bpm
                myLastBeat = it
            }
        }

        // Messages → notifications, then remove them from the inbox.
        launch {
            // A message whose delete failed comes back on the next change: show it only once.
            val shown = mutableSetOf<String>()
            HeartRepository.observeInbox(session).safe().collect { messages ->
                messages.forEach { message ->
                    if (shown.add(message.id)) Notifications.showMessage(this@HeartLinkService, message, quiet = quietNow())
                    runCatching { HeartRepository.deleteMessage(session, message.id) }
                        .onFailure { Log.w(TAG, "Couldn't delete message", it) }
                }
            }
        }

        // Partner's heartbeat → vibration.
        launch {
            HeartRepository.observeBuzz(session).safe().collect { buzz -> if (buzz != null) playBuzz(buzz) }
        }

        // Whether this phone can reach its watch → shown on the partner's card. Written only when
        // it changes, plus a refresh every so often so the partner can trust it's current.
        launch {
            var reported: Boolean? = null
            var reportedAt = 0L
            var syncAskedAt = 0L
            var syncInterval = WATCH_SYNC_MIN_MS
            while (true) {
                val connected = runCatching { WatchNodes.reachable(this@HeartLinkService).isNotEmpty() }.getOrNull()
                val now = System.currentTimeMillis()
                // No fresh reading from the watch: ask it to check in. It re-arms its background
                // monitoring, retries anything stuck, and sends back what it has for this phone to
                // upload. Asks less often while nothing comes back (off the wrist, say).
                val beat = myLastBeat
                // The reading's time is server time.
                val fresh = beat != null && beat.source == Sources.WATCH &&
                    HeartRepository.toServerTime(now) - beat.timestamp < WATCH_STALE_MS
                if (fresh) {
                    syncInterval = WATCH_SYNC_MIN_MS
                } else if (connected == true && now - syncAskedAt >= syncInterval) {
                    sendToWatch(WearPaths.SYNC_REQUEST, ByteArray(0))
                    if (syncAskedAt != 0L) syncInterval = (syncInterval * 2).coerceAtMost(WATCH_SYNC_MAX_MS)
                    syncAskedAt = now
                }
                if (connected != null && (connected != reported || now - reportedAt > WATCH_LINK_REFRESH_MS)) {
                    runCatching { HeartRepository.reportWatchLink(session, connected) }
                        .onSuccess {
                            reported = connected
                            reportedAt = now
                        }
                        .onFailure { Log.w(TAG, "Couldn't report watch connection", it) }
                }
                delay(WATCH_LINK_CHECK_MS)
            }
        }

        // Screen-on and unlock only fire once, so keep checking every minute: the phone in use, a
        // call, being on the move. Without this, using the phone for a while shows as inactive,
        // then away, and doesn't clear "asleep" if the unlock was missed.
        launch {
            while (true) {
                delay(ACTIVE_WRITE_INTERVAL_MS)
                markActive()
                if (inCall()) noteCall()
                if (Motion.isMoving(this@HeartLinkService)) noteMoving()
            }
        }

        // Heartbeats held back during quiet hours → play them once quiet hours end.
        launch {
            while (true) {
                replayIfDue()
                delay(REPLAY_CHECK_MS)
            }
        }

        // Partner wakes up / falls asleep → gentle notification. Getting up briefly in the night
        // ("woke up") still counts as asleep, so it doesn't announce "awake" then "asleep".
        launch {
            var previous: Boolean? = null
            combine(
                HeartRepository.observePresence(session.coupleId, session.partnerRole).safe(),
                HeartRepository.observeMembers(session.coupleId).safe(),
            ) { presence, members -> presence to members[session.partnerRole] }
                .collect { (presence, name) ->
                    presence ?: return@collect
                    val decision = presence.sleepDecision(HeartRepository.toServerTime(System.currentTimeMillis()))
                    val asleep = decision.state != SleepState.Awake
                    // When it changed, from whichever source decided it.
                    val at = decision.since ?: return@collect
                    val was = previous
                    previous = asleep
                    // The first value is just the current state, not a change.
                    if (was == null || was == asleep) return@collect
                    announceMoment(name, asleep, at)
                }
        }

        // Partner is looking → ask the watch for live readings.
        launch {
            HeartRepository.observeWatching(session).safe().collect { ts ->
                val now = HeartRepository.toServerTime(System.currentTimeMillis())
                if (ts != null && now - ts < WATCHING_FRESH_MS) sendToWatch(WearPaths.LIVE_REQUEST, ByteArray(0))
            }
        }

        // Always-on notification.
        launch {
            // Ticks only while the screen is on: nobody reads "3m ago" on a dark screen, and
            // rebuilding the notification every few seconds all night costs battery. New data from the
            // partner still updates it straight away.
            launch {
                while (true) {
                    if (getSystemService(PowerManager::class.java).isInteractive) clock.value = System.currentTimeMillis()
                    delay(NOTIFICATION_TICK_MS)
                }
            }
            val ticker = clock
            combine(
                HeartRepository.observeMembers(session.coupleId).safe(),
                HeartRepository.observeBeat(session.coupleId, session.partnerRole).safe(),
                HeartRepository.observePresence(session.coupleId, session.partnerRole).safe(),
                ticker,
                // Server time, like the app's screen, so both agree on the partner's status.
                HeartRepository.serverTimeOffset().safe(),
            ) { members, beat, presence, _, offset ->
                // The ticker only says when to rebuild. The time itself is read when the
                // notification is built: the ticker stops while the screen is off, and new data
                // judged against a time hours old showed the wrong status and "live" beats.
                NotificationState(members[session.partnerRole], beat, presence, offset)
            }
                .collect { state ->
                    latestState = state
                    updateStatusNotification(state)
                }
        }
    }

    private fun <T> Flow<T>.safe(): Flow<T> = catch { Log.w(TAG, "Listener failed", it) }

    // ---- Quiet hours and moments ---------------------------------------------------------

    /** True while things should arrive silently on this phone. */
    private fun quietNow(): Boolean {
        val now = System.currentTimeMillis()
        val online = inUse() || now - lastInteraction < StatusRules.INACTIVE_AFTER_MS
        // Using the phone means awake, even if the watch hasn't noticed yet.
        val asleep = mySleep?.isAsleep(HeartRepository.toServerTime(now)) == true && !online
        return isQuiet(Settings.quietHours(this), ZoneId.systemDefault().id, now, asleep, online)
    }

    private fun reportQuietHours(current: Session) {
        scope.launch {
            runCatching { HeartRepository.reportQuietHours(current, Settings.quietHours(this@HeartLinkService)) }
                .onFailure { Log.w(TAG, "Couldn't report quiet hours", it) }
        }
    }

    private fun announceMoment(name: String?, asleep: Boolean, at: Long) {
        if (!Settings.wakeSleepNotifications(this)) return
        // [at] is server time.
        if (HeartRepository.toServerTime(System.currentTimeMillis()) - at > Moments.FRESH_MS) return
        if (at <= Settings.lastMomentAt(this)) return
        Settings.setLastMomentAt(this, at)
        val who = name ?: "Your partner"
        val text = if (asleep) "$who is asleep 🌙" else "$who is awake ☀️"
        Notifications.showMoment(this, text, quiet = quietNow())
    }

    // ---- Activity ------------------------------------------------------------------------

    /**
     * The screen also lights up by itself (notifications, a heartbeat arriving, lift to wake), so
     * only an unlocked, switched-on phone counts as being used. Otherwise someone asleep reads as
     * online every time their phone lights up.
     */
    private fun inUse(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** On a phone or video call (works with the screen off; no permission needed). */
    private fun inCall(): Boolean = runCatching {
        val mode = getSystemService(AudioManager::class.java).mode
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }.getOrDefault(false)

    private fun markActive() {
        if (inUse()) markEngaged(ActivityKind.Phone)
    }

    /**
     * Records direct use of the phone ([ActivityKind.direct]) for the activity and sleep models,
     * and shares it as lastActive (at most once a minute).
     */
    private fun markEngaged(kind: ActivityKind) {
        val current = session ?: return
        val now = System.currentTimeMillis()
        lastInteraction = now
        if (activityLog.add(kind, HeartRepository.toServerTime(now))) decideSleep()
        if (now - lastActiveWrite < ACTIVE_WRITE_INTERVAL_MS) return
        lastActiveWrite = now
        lastActiveWriteServer = HeartRepository.toServerTime(now)
        scope.launch {
            runCatching { HeartRepository.markActive(current, myPresence) }.onFailure { Log.w(TAG, "markActive failed", it) }
        }
    }

    /**
     * On a call: recorded for the activity model every minute and shared as callAt every
     * [CALL_REPORT_MS]. Kept apart from use: someone asleep on an all-night call (e.g. a Discord
     * sleep call) isn't awake, and mustn't count as "using my phone" for quiet hours either.
     */
    private fun noteCall() {
        val current = session ?: return
        val now = System.currentTimeMillis()
        activityLog.add(ActivityKind.Call, HeartRepository.toServerTime(now))
        // Every few minutes is enough (a call counts as ongoing for 10 minutes after each report),
        // and keeps an all-night call from writing every minute.
        if (now - lastCallWrite < CALL_REPORT_MS) return
        lastCallWrite = now
        scope.launch {
            runCatching { HeartRepository.markOnCall(current) }.onFailure { Log.w(TAG, "markOnCall failed", it) }
        }
    }

    /**
     * On the move (or just stopped): recorded for the activity model, and shared as movingAt every
     * [MOVING_REPORT_MS] while it lasts, so the partner sees inactive rather than away.
     */
    private fun noteMoving() {
        val current = session ?: return
        val now = System.currentTimeMillis()
        val moving = Motion.isMoving(this, now)
        // Just stopped: the moving ended when the phone said so.
        val at = if (moving) now else Motion.changedAt(this)?.takeIf { now - it < StatusRules.MOVING_RECENT_MS } ?: return
        activityLog.add(ActivityKind.Moving, HeartRepository.toServerTime(at))
        if (moving && now - lastMovingWrite < MOVING_REPORT_MS) return
        lastMovingWrite = now
        scope.launch {
            runCatching { HeartRepository.markMoving(current) }.onFailure { Log.w(TAG, "markMoving failed", it) }
        }
    }

    /** Checks last night's guesses against the recorded night, once it's there, and learns from them. */
    private fun checkNight() {
        val changed = NightJournal.check(
            this,
            finished = healthSleeps + listOfNotNull(SleepApi.lastSleep(this)),
            uses = activityLog.engagedRuns(),
            readings = SleepApi.readings(this),
            now = HeartRepository.toServerTime(System.currentTimeMillis()),
        )
        if (changed) Log.d(TAG, "Sleep tuning now ${NightJournal.tuning(this)}")
    }

    /**
     * Runs [SleepModel] over everything this phone knows about this person's sleep and publishes
     * the verdict when it changes. Cheap: no network unless the verdict changes, which during the
     * day is never (the watch says awake).
     */
    private fun decideSleep() {
        val current = session ?: return
        val presence = myPresence ?: return
        val evidence = SleepEvidence(
            watchAsleep = presence.asleep,
            watchChangedAt = presence.asleepAt,
            uses = activityLog.engagedRuns(),
            finishedSleeps = healthSleeps + listOfNotNull(SleepApi.lastSleep(this)),
            readings = SleepApi.readings(this),
        )
        val decision = SleepModel.decide(evidence, HeartRepository.toServerTime(System.currentTimeMillis()), NightJournal.tuning(this))
        mySleep = decision
        if (decision.sameAs(presence.sleep)) return
        Log.d(TAG, "Sleep: ${decision.state} since ${decision.since} (${decision.reason})")
        scope.launch {
            runCatching { HeartRepository.publishSleep(current, decision) }.onFailure { Log.w(TAG, "Couldn't publish sleep", it) }
        }
    }

    // ---- Heartbeats ----------------------------------------------------------------------

    private fun sendMyHeartbeat(current: Session) {
        scope.launch {
            // Started from the notification just now, the listeners may not have loaded yet:
            // wait briefly rather than sending a made-up 70 bpm with no name.
            val bpm = myLastBpm
                ?: withTimeoutOrNull(LOAD_WAIT_MS) { HeartRepository.observeBeat(current.coupleId, current.role).first() }?.bpm
                ?: DEFAULT_BPM
            val name = myName
                ?: withTimeoutOrNull(LOAD_WAIT_MS) { HeartRepository.observeMembers(current.coupleId).first()[current.role] }
            val text = when (HeartRepository.trySend { HeartRepository.sendBuzz(current, bpm, name) }) {
                SendResult.Sent -> "Heartbeat sent"
                SendResult.Queued -> "No connection. It'll send when you're back online"
                SendResult.Failed -> "Couldn't send your heartbeat"
            }
            Toast.makeText(this@HeartLinkService, text, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun playBuzz(buzz: Buzz) {
        // Play each buzz once, and skip ones that arrive long after they were sent.
        if (buzz.timestamp <= Settings.lastHandledBuzz(this)) return
        Settings.setLastHandledBuzz(this, buzz.timestamp)
        // The sender stamps server-corrected time, so compare with server-corrected time too.
        val age = HeartRepository.serverNow() - buzz.timestamp
        if (age > BUZZ_MAX_AGE_MS) {
            // Too late to vibrate (it was sent while one of the phones was offline), but don't
            // lose it: show it silently with the time it was sent.
            if (age < REPLAY_MAX_AGE_MS) {
                Notifications.showHeartbeat(this, buzz.fromName, buzz.bpm, localOnly = true, quiet = true, sentAt = buzz.timestamp)
            }
            return
        }

        if (quietNow()) {
            // Quiet hours: no vibration anywhere, just a silent notification to find later,
            // and (if enabled) a replay once quiet hours end.
            Notifications.showHeartbeat(this, buzz.fromName, buzz.bpm, localOnly = true, quiet = true)
            if (Settings.replayAfterQuiet(this)) Settings.addPendingReplay(this, buzz.bpm, buzz.fromName, buzz.timestamp)
            return
        }
        val sentToWatch = playHeartbeat(buzz.bpm)
        Notifications.showHeartbeat(this, buzz.fromName, buzz.bpm, localOnly = sentToWatch)
    }

    /** Plays a heartbeat on the watch if one is connected, otherwise on this phone. */
    private suspend fun playHeartbeat(bpm: Int): Boolean {
        val playback = BuzzPlayback(bpm, Settings.hapticBeats(this), Settings.hapticStrength(this))
        val sentToWatch = sendToWatch(WearPaths.BUZZ, playback.encode().toByteArray())
        if (!sentToWatch) vibrate(playback)
        return sentToWatch
    }

    private var replaying = false

    /** If heartbeats were held back during quiet hours and it's no longer quiet, play them now. */
    private suspend fun replayIfDue() {
        if (replaying) return
        val pending = Settings.pendingReplay(this) ?: return
        if (System.currentTimeMillis() - pending.firstAt > REPLAY_MAX_AGE_MS) {
            Settings.clearPendingReplay(this)
            return
        }
        if (quietNow()) return
        replaying = true
        try {
            Settings.clearPendingReplay(this)
            val sentToWatch = playHeartbeat(pending.bpm)
            Notifications.showReplay(this, pending.fromName, pending.count, pending.bpm, localOnly = sentToWatch)
        } finally {
            replaying = false
        }
    }

    private fun vibrate(playback: BuzzPlayback) {
        val (timings, amplitudes) = playback.waveform()
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.os.Vibrator::class.java)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    /** Sends to every reachable HeartLink watch. Returns true if at least one received it. */
    private suspend fun sendToWatch(path: String, payload: ByteArray): Boolean = WatchNodes.send(this, path, payload)

    // ---- Always-on notification ----------------------------------------------------------

    /** What the notification shows. [offset] is the server time offset; the time is read at build time. */
    private data class NotificationState(val partnerName: String?, val beat: Reading?, val presence: Presence?, val offset: Long) {
        /** Server time now, the same clock the app's screen uses. */
        val now: Long get() = System.currentTimeMillis() + offset
    }

    private var latestState: NotificationState? = null
    private val clock = MutableStateFlow(System.currentTimeMillis())

    private fun refreshNotificationNow() {
        updateStatusNotification(latestState)
    }

    @Suppress("MissingPermission") // Guarded by areNotificationsEnabled().
    private fun updateStatusNotification(state: NotificationState?) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) manager.notify(Notifications.ID_STATUS, buildStatusNotification(state))
    }

    private fun buildStatusNotification(state: NotificationState?): Notification {
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_heart)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(Notifications.openAppIntent(this))
            // Android 14+ lets people swipe away even foreground-service notifications and ignores
            // setOngoing. When that happens, post it straight back.
            .setDeleteIntent(
                android.app.PendingIntent.getService(
                    this,
                    2,
                    Intent(this, HeartLinkService::class.java).setAction(ACTION_REFRESH),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        val name = state?.partnerName
        // One reading of the time for everything below, so its parts agree with each other.
        val now = state?.now ?: 0L
        if (state == null || name == null || !Settings.richNotification(this)) {
            builder.setContentTitle("HeartLink").setContentText("Connected")
        } else {
            val beat = state.beat
            val age = beat?.let { now - it.timestamp }
            val bpmText = when {
                beat == null -> "no reading yet"
                age != null && age < LIVE_THRESHOLD_MS -> "${beat.bpm} bpm"
                age != null -> "${beat.bpm} bpm · ${com.heartlink.shared.ageText(age)}"
                else -> "${beat.bpm} bpm"
            }
            val current = partnerStatus(state.presence, beat, now)
            val status = statusLabel(current, state.presence, beat, now)
            val time = localTimeText(state.presence?.timeZone, now)
            val battery = state.presence?.phoneBattery?.let { Battery.label(it, "📱", now) }
            if (current == PartnerStatus.NoConnection) {
                // The timer is the news; the old reading is just context. No battery age here,
                // because the title's timer already says how old everything is.
                val lastBattery = state.presence?.phoneBattery?.let { Battery.label(it, "📱", it.updatedAt ?: now) }
                builder.setContentTitle("$name · $status")
                    .setContentText(
                        listOfNotNull(beat?.let { "Last known ${it.bpm} bpm" }, time?.let { "$it there" }, lastBattery)
                            .joinToString(" · "),
                    )
            } else {
                builder.setContentTitle("$name · $bpmText")
                    .setContentText(
                        listOfNotNull(status.ifEmpty { null }, time?.let { "$it there" }, battery).joinToString(" · "),
                    )
            }
        }

        builder.addAction(
            0,
            "Send my heartbeat",
            android.app.PendingIntent.getService(
                this,
                1,
                Intent(this, HeartLinkService::class.java).setAction(ACTION_SEND_HEARTBEAT),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return builder.build()
    }

    companion object {
        private const val TAG = "HeartLinkService"
        const val ACTION_SEND_HEARTBEAT = "com.heartlink.phone.SEND_HEARTBEAT"
        const val ACTION_REFRESH = "com.heartlink.phone.REFRESH"
        const val ACTION_QUIET_CHANGED = "com.heartlink.phone.QUIET_CHANGED"
        const val ACTION_TRY_HAPTICS = "com.heartlink.phone.TRY_HAPTICS"
        const val ACTION_SLEEP_SOURCES_CHANGED = "com.heartlink.phone.SLEEP_SOURCES_CHANGED"

        private val ACTIVE_WRITE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1)
        private val SLEEP_CHECK_MS = TimeUnit.MINUTES.toMillis(10)
        private val OWN_WRITE_SLACK_MS = TimeUnit.SECONDS.toMillis(30)
        private val CALL_REPORT_MS = TimeUnit.MINUTES.toMillis(5)
        private val WATCHING_FRESH_MS = TimeUnit.SECONDS.toMillis(60)
        private val BUZZ_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(2)
        private val REPLAY_CHECK_MS = TimeUnit.MINUTES.toMillis(1)
        private val WATCH_LINK_CHECK_MS = TimeUnit.MINUTES.toMillis(1)
        private val WATCH_LINK_REFRESH_MS = TimeUnit.MINUTES.toMillis(30)

        /** No watch reading for this long → ask the watch to check in. */
        private val WATCH_STALE_MS = TimeUnit.MINUTES.toMillis(15)
        private val WATCH_SYNC_MIN_MS = TimeUnit.MINUTES.toMillis(5)
        private val WATCH_SYNC_MAX_MS = TimeUnit.MINUTES.toMillis(60)

        /** A replay older than this (a quiet period that never ended, for example) is dropped. */
        private val REPLAY_MAX_AGE_MS = TimeUnit.HOURS.toMillis(18)
        /** While the screen is on. Status changes (online → inactive, "live" → "25s ago") show within this. */
        private val NOTIFICATION_TICK_MS = TimeUnit.SECONDS.toMillis(10)
        private const val DEFAULT_BPM = 70
        private val LOAD_WAIT_MS = TimeUnit.SECONDS.toMillis(5)

        /** Starts (or nudges) the service if linked and the background setting is on. */
        fun start(ctx: Context, action: String? = null) {
            if (Identity.get(ctx) == null || !Settings.runInBackground(ctx)) return
            val intent = Intent(ctx, HeartLinkService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException if called from the background.
                Log.w(TAG, "Couldn't start service", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HeartLinkService::class.java))
        }
    }
}
