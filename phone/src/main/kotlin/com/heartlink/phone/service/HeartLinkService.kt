package com.heartlink.phone.service

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import com.heartlink.phone.R
import com.heartlink.shared.Battery
import com.heartlink.shared.Buzz
import com.heartlink.shared.Moments
import com.heartlink.shared.StatusRules
import com.heartlink.shared.isQuiet
import com.heartlink.shared.Devices
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import com.heartlink.shared.LIVE_THRESHOLD_MS
import com.heartlink.shared.Presence
import com.heartlink.shared.Reading
import com.heartlink.shared.Session
import com.heartlink.shared.Vibration
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    private var myLastBpm: Int? = null
    private var myName: String? = null
    private var myPresence: Presence? = null
    private var lastInteraction = 0L

    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = markActive()
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
            ACTION_QUIET_CHANGED -> reportQuietHours(current)
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
        launch { Battery.reportIfChanged(this@HeartLinkService, session, Devices.PHONE) }
        launch { HeartRepository.maintainPresence(session, tz).safe().collect {} }
        reportQuietHours(session)
        launch {
            HeartRepository.observePresence(session.coupleId, session.role).safe().collect { myPresence = it }
        }

        launch {
            HeartRepository.observeMembers(session.coupleId).safe().collect { myName = it[session.role] }
        }
        launch {
            HeartRepository.observeBeat(session.coupleId, session.role).safe().collect { myLastBpm = it?.bpm }
        }

        // Messages → notifications, then remove them from the inbox.
        launch {
            HeartRepository.observeInbox(session).safe().collect { messages ->
                messages.forEach { message ->
                    Notifications.showMessage(this@HeartLinkService, message, quiet = quietNow())
                    runCatching { HeartRepository.deleteMessage(session, message.id) }
                        .onFailure { Log.w(TAG, "Couldn't delete message", it) }
                }
            }
        }

        // Partner's heartbeat → vibration.
        launch {
            HeartRepository.observeBuzz(session).safe().collect { buzz -> if (buzz != null) playBuzz(buzz) }
        }

        // Partner wakes up / falls asleep → gentle notification.
        launch {
            var previous: Boolean? = null
            combine(
                HeartRepository.observePresence(session.coupleId, session.partnerRole).safe(),
                HeartRepository.observeMembers(session.coupleId).safe(),
            ) { presence, members -> presence to members[session.partnerRole] }
                .collect { (presence, name) ->
                    val asleep = presence?.asleep ?: return@collect
                    val at = presence.asleepAt ?: return@collect
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
                if (ts != null && System.currentTimeMillis() - ts < WATCHING_FRESH_MS) sendToWatch(WearPaths.LIVE_REQUEST, ByteArray(0))
            }
        }

        // Always-on notification.
        launch {
            val ticker = flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(NOTIFICATION_TICK_MS)
                }
            }
            combine(
                HeartRepository.observeMembers(session.coupleId).safe(),
                HeartRepository.observeBeat(session.coupleId, session.partnerRole).safe(),
                HeartRepository.observePresence(session.coupleId, session.partnerRole).safe(),
                ticker,
            ) { members, beat, presence, now -> NotificationState(members[session.partnerRole], beat, presence, now) }
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
        val presence = myPresence
        val asleepAt = presence?.asleepAt
        val asleep = presence?.asleep == true && asleepAt != null && now - asleepAt < StatusRules.ASLEEP_STALE_MS
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val online = interactive || now - lastInteraction < StatusRules.INACTIVE_AFTER_MS
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
        if (System.currentTimeMillis() - at > Moments.FRESH_MS) return
        if (at <= Settings.lastMomentAt(this)) return
        Settings.setLastMomentAt(this, at)
        val who = name ?: "Your partner"
        val text = if (asleep) "$who is asleep 🌙" else "$who is awake ☀️"
        Notifications.showMoment(this, text, quiet = quietNow())
    }

    // ---- Activity ------------------------------------------------------------------------

    private fun markActive() {
        val current = session ?: return
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive) return
        val now = System.currentTimeMillis()
        lastInteraction = now
        if (now - lastActiveWrite < ACTIVE_WRITE_INTERVAL_MS) return
        lastActiveWrite = now
        scope.launch {
            runCatching { HeartRepository.markActive(current) }.onFailure { Log.w(TAG, "markActive failed", it) }
        }
    }

    // ---- Heartbeats ----------------------------------------------------------------------

    private fun sendMyHeartbeat(current: Session) {
        val bpm = myLastBpm ?: DEFAULT_BPM
        scope.launch {
            runCatching { HeartRepository.sendBuzz(current, bpm, myName) }
                .onFailure { Log.w(TAG, "sendBuzz failed", it) }
        }
    }

    private suspend fun playBuzz(buzz: Buzz) {
        // Play each buzz once, and skip ones that arrive long after they were sent.
        if (buzz.timestamp <= Settings.lastHandledBuzz(this)) return
        Settings.setLastHandledBuzz(this, buzz.timestamp)
        if (System.currentTimeMillis() - buzz.timestamp > BUZZ_MAX_AGE_MS) return

        if (quietNow()) {
            // Quiet hours: no vibration anywhere, just a silent notification to find later.
            Notifications.showHeartbeat(this, buzz.fromName, buzz.bpm, localOnly = true, quiet = true)
            return
        }
        val sentToWatch = sendToWatch(WearPaths.BUZZ, buzz.bpm.toString().toByteArray())
        if (!sentToWatch) vibrate(buzz.bpm)
        Notifications.showHeartbeat(this, buzz.fromName, buzz.bpm, localOnly = sentToWatch)
    }

    private fun vibrate(bpm: Int) {
        val (timings, amplitudes) = Vibration.heartbeatWaveform(bpm)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.os.Vibrator::class.java)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    /** Sends to every connected watch. Returns true if at least one received it. */
    private suspend fun sendToWatch(path: String, payload: ByteArray): Boolean = try {
        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        nodes.map { node ->
            runCatching { Wearable.getMessageClient(this).sendMessage(node.id, path, payload).await() }.isSuccess
        }.any { it }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't reach watch", e)
        false
    }

    // ---- Always-on notification ----------------------------------------------------------

    private data class NotificationState(val partnerName: String?, val beat: Reading?, val presence: Presence?, val now: Long)

    private var latestState: NotificationState? = null

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
        if (state == null || name == null || !Settings.richNotification(this)) {
            builder.setContentTitle("HeartLink").setContentText("Connected")
        } else {
            val beat = state.beat
            val age = beat?.let { state.now - it.timestamp }
            val bpmText = when {
                beat == null -> "no reading yet"
                age != null && age < LIVE_THRESHOLD_MS -> "${beat.bpm} bpm"
                age != null -> "${beat.bpm} bpm · ${com.heartlink.shared.ageText(age)}"
                else -> "${beat.bpm} bpm"
            }
            val current = partnerStatus(state.presence, beat, state.now)
            val status = statusLabel(current, state.presence, beat, state.now)
            val time = localTimeText(state.presence?.timeZone, state.now)
            val battery = state.presence?.phoneBattery?.let { Battery.label(it, "📱", state.now) }
            if (current == PartnerStatus.NoConnection) {
                // The timer is the news; the old reading is just context.
                builder.setContentTitle("$name · $status")
                    .setContentText(
                        listOfNotNull(beat?.let { "Last known ${it.bpm} bpm" }, time?.let { "$it there" }, battery)
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

        private val ACTIVE_WRITE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1)
        private val WATCHING_FRESH_MS = TimeUnit.SECONDS.toMillis(60)
        private val BUZZ_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(2)
        private val NOTIFICATION_TICK_MS = TimeUnit.SECONDS.toMillis(30)
        private const val DEFAULT_BPM = 70

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
