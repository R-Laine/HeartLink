package com.heartlink.shared

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseException
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Firebase Realtime Database layout (see firebase/database.rules.json):
 *
 * ```
 * /couples/{coupleId}/devices/{uid}          { role, invite? }  devices allowed to read/write this couple
 * /couples/{coupleId}/members/{a|b}/name     set once, when the seat is claimed
 * /couples/{coupleId}/beats/{a|b}            { bpm, ts, source }
 * /couples/{coupleId}/pendingInvite          code of the unused partner invite, if any
 * /couples/{coupleId}/presence/{a|b}         { connected, lastSeen, lastActive, asleep, asleepAt, tz,
 *                                              battery/{phone|watch}: { level, charging, ts },
 *                                              checkIn: { text, ts }, quiet: { enabled, start, end, allowWhenOnline } }
 * /couples/{coupleId}/countdown              { date: "2026-12-20" }  next time together
 * /couples/{coupleId}/messages/{a|b}/{id}    { text, ts, from }  inbox, deleted once shown
 * /couples/{coupleId}/buzz/{a|b}             { bpm, ts, from }   latest "feel my heartbeat"
 * /couples/{coupleId}/watching/{a|b}         ts when the partner last looked at this person
 * /invites/{code}                            { couple, role, expires, from?, name? }  deleted when used
 * ```
 *
 * The couple ID is a random push key that's never shown. Short codes are only one-time invites.
 */
object HeartRepository {
    private const val TAG = "HeartRepository"
    private const val MIN_WRITE_INTERVAL_MS = 3_000L
    private const val CODE_ATTEMPTS = 5
    private val PARTNER_INVITE_TTL_MS = TimeUnit.DAYS.toMillis(7)
    val DEVICE_INVITE_TTL_MS = TimeUnit.MINUTES.toMillis(15)
    private val SIGN_IN_RETRY_MS = TimeUnit.SECONDS.toMillis(5)
    private val SEND_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15)
    private val OFFSET_WAIT_MS = TimeUnit.SECONDS.toMillis(2)
    private val SAMPLE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30)

    /** Buzz bpm limits; the database rules refuse anything outside them. */
    private const val BUZZ_MIN_BPM = 20
    private const val BUZZ_MAX_BPM = 250

    /** Pause before listening again after the database refuses a listener (doubles up to the max). */
    private val LISTEN_RETRY_MIN_MS = TimeUnit.SECONDS.toMillis(5)
    private val LISTEN_RETRY_MAX_MS = TimeUnit.MINUTES.toMillis(2)

    private val lastWrite = AtomicLong(0L)

    private val _accessDenied = MutableStateFlow(false)

    /**
     * True once the database has refused this device: it's no longer part of the couple it
     * thinks it's linked to (unlinked elsewhere, or a link from an older Firebase project).
     */
    val accessDenied: StateFlow<Boolean> = _accessDenied

    /**
     * Checks this device is still allowed into its couple. True if it is, false if the database
     * says it isn't, null if that couldn't be checked (offline, for example).
     */
    suspend fun isStillLinked(session: Session): Boolean? = try {
        val uid = ensureSignedIn()
        val exists = couple(session.coupleId).child("devices").child(uid).get().await().exists()
        if (!exists) {
            lastDenied = "not listed in couples/${session.coupleId}/devices"
            _accessDenied.value = true
        }
        exists
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val denied = (e as? DatabaseException)?.message?.contains("permission", ignoreCase = true) == true ||
            e.message?.contains("permission", ignoreCase = true) == true
        Log.w(TAG, "Couldn't check link (denied=$denied)", e)
        if (denied) {
            lastDenied = "couples/${session.coupleId}/devices"
            _accessDenied.value = true
            false
        } else {
            null
        }
    }

    /** Call after unlinking or linking again, so [accessDenied] starts fresh. */
    fun resetAccessDenied() {
        _accessDenied.value = false
    }

    private val database get() = Firebase.database
    private val root get() = database.reference
    private fun couple(id: String): DatabaseReference = root.child("couples").child(id)
    private fun invite(code: String): DatabaseReference = root.child("invites").child(code)

    /** Signs in anonymously if needed and returns this device's user ID. */
    suspend fun ensureSignedIn(): String {
        val auth = Firebase.auth
        auth.currentUser?.let { return it.uid }
        // Several parts of the app ask at once on first launch. Without the lock each would
        // create its own anonymous user, and the watch could hand its phone one ID while
        // reading with another, which the database refuses.
        return signInLock.withLock {
            val user = auth.currentUser ?: auth.signInAnonymously().await().user
            checkNotNull(user) { "Anonymous sign-in returned no user" }.uid
        }
    }

    private val signInLock = Mutex()

    /** This device's user ID, if signed in. Shown when troubleshooting a refused link. */
    val currentUid: String? get() = Firebase.auth.currentUser?.uid

    /** The last database path that refused this device, for troubleshooting. */
    @Volatile
    var lastDenied: String? = null
        private set

    // ---- Linking -------------------------------------------------------------------------

    /** Creates a new couple with [name] in seat A, plus a 7-day invite for their partner. */
    suspend fun createCouple(name: String): Session {
        val uid = ensureSignedIn()
        val id = checkNotNull(root.child("couples").push().key)
        withFreshCode { code ->
            root.updateChildren(
                mapOf(
                    "couples/$id/devices/$uid" to mapOf("role" to Roles.A),
                    "couples/$id/members/${Roles.A}/name" to name,
                    "couples/$id/pendingInvite" to code,
                    "invites/$code" to inviteValue(id, Roles.B, PARTNER_INVITE_TTL_MS, fromName = name, seatName = null),
                ),
            ).await()
        }
        return Session(id, Roles.A)
    }

    /** Looks up an unused, unexpired invite, or returns null. */
    suspend fun lookupInvite(rawCode: String): Invite? {
        ensureSignedIn()
        val code = InviteCode.normalize(rawCode)
        if (!InviteCode.isValid(code)) return null
        val snap = invite(code).get().await()
        val coupleId = snap.child("couple").getValue(String::class.java) ?: return null
        val role = snap.child("role").getValue(String::class.java)?.takeIf { it in Roles.all } ?: return null
        val expires = snap.child("expires").getValue(Long::class.java) ?: return null
        if (expires <= System.currentTimeMillis()) return null
        return Invite(
            code = code,
            coupleId = coupleId,
            role = role,
            fromName = snap.child("from").getValue(String::class.java),
            seatName = snap.child("name").getValue(String::class.java),
            expiresAt = expires,
        )
    }

    /**
     * Joins using [invite], consuming it in the same atomic write so it can only be used once.
     * [name] is required when the invite is for a new partner ([Invite.seatName] is null).
     */
    suspend fun joinWithInvite(invite: Invite, name: String?): Session {
        val uid = ensureSignedIn()
        val id = invite.coupleId
        val updates = mutableMapOf<String, Any?>(
            "couples/$id/devices/$uid" to mapOf("role" to invite.role, "invite" to invite.code),
            "invites/${invite.code}" to null,
        )
        if (invite.seatName == null) {
            updates["couples/$id/members/${invite.role}/name"] = requireNotNull(name) { "Name required" }
            updates["couples/$id/pendingInvite"] = null
        }
        root.updateChildren(updates).await()
        return Session(id, invite.role)
    }

    /** Replaces an expired (or unwanted) partner invite with a fresh 7-day one. */
    suspend fun renewPartnerInvite(session: Session, fromName: String?, oldCode: String?): String {
        ensureSignedIn()
        val oldExists = oldCode != null && invite(oldCode).get().await().exists()
        return withFreshCode { code ->
            val updates = mutableMapOf<String, Any?>(
                "couples/${session.coupleId}/pendingInvite" to code,
                "invites/$code" to inviteValue(session.coupleId, Roles.B, PARTNER_INVITE_TTL_MS, fromName, seatName = null),
            )
            if (oldExists) updates["invites/$oldCode"] = null
            root.updateChildren(updates).await()
        }
    }

    /** A 15-minute, one-time code that links another device to [role]'s existing seat. */
    suspend fun createDeviceInvite(session: Session, role: String, seatName: String, fromName: String?): String {
        ensureSignedIn()
        return withFreshCode { code ->
            invite(code).setValue(inviteValue(session.coupleId, role, DEVICE_INVITE_TTL_MS, fromName, seatName)).await()
        }
    }

    /** Lets another device (e.g. this person's watch, identified by [deviceUid]) into the couple. */
    suspend fun addDevice(session: Session, deviceUid: String) {
        ensureSignedIn()
        val ref = couple(session.coupleId).child("devices").child(deviceUid)
        if (ref.get().await().exists()) return
        ref.setValue(mapOf("role" to session.role)).await()
    }

    /**
     * Removes this device's access, along with [watchUids]: this person's watches that this phone
     * linked, so they stop sharing too. Other devices and the partner are unaffected.
     */
    suspend fun leave(session: Session, watchUids: Collection<String> = emptyList()) {
        val uid = ensureSignedIn()
        val devices = couple(session.coupleId).child("devices")
        val updates = mutableMapOf<String, Any?>(uid to null)
        if (watchUids.isNotEmpty()) {
            // Only watches still listed for this seat: removing a missing entry is refused, and
            // that would fail the whole write, including this phone's own removal.
            val listed = devices.get().await()
            watchUids
                .filter { it != uid && listed.child(it).child("role").getValue(String::class.java) == session.role }
                .forEach { updates[it] = null }
        }
        devices.updateChildren(updates).await()
    }

    // ---- Heartbeats ----------------------------------------------------------------------

    /**
     * Uploads a live [bpm] for this device's seat, stamped with server time. Throttled to one
     * write every 3 s unless [force] is set.
     */
    suspend fun upload(session: Session, bpm: Int, force: Boolean = false, source: String = Sources.WATCH): Boolean {
        val now = System.currentTimeMillis()
        if (force) {
            lastWrite.set(now)
        } else {
            val previous = lastWrite.get()
            if (now - previous < MIN_WRITE_INTERVAL_MS || !lastWrite.compareAndSet(previous, now)) return false
        }
        return try {
            ensureSignedIn()
            beat(session)
                .setValue(mapOf("bpm" to bpm, "ts" to ServerValue.TIMESTAMP, "source" to source))
                .await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed", e)
            false
        }
    }

    /**
     * Uploads a reading measured at [timestampMs] (this device's clock, e.g. from Health Connect
     * or a watch's background batch), but only if it is newer than what is already stored, so it
     * never replaces fresher live watch data. Stored in server time, like live readings: that's
     * what readers compare it with, and the rules refuse a time ahead of the server's.
     */
    suspend fun uploadSample(session: Session, bpm: Int, timestampMs: Long, source: String): Boolean {
        ensureSignedIn()
        val ts = toServerTime(timestampMs).coerceAtMost(serverNow())
        val ref = beat(session)
        return suspendCancellableCoroutine { cont ->
            ref.runTransaction(object : Transaction.Handler {
                override fun doTransaction(current: MutableData): Transaction.Result {
                    val existingTs = current.child("ts").getValue(Long::class.java)
                    if (existingTs != null && existingTs >= ts) return Transaction.abort()
                    current.value = mapOf("bpm" to bpm, "ts" to ts, "source" to source)
                    return Transaction.success(current)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    if (error != null) cont.resumeWithException(error.toException()) else cont.resume(committed)
                }
            })
        }
    }

    /**
     * Uploads a reading the watch measured at [measuredAt] (its own clock). Background readings
     * arrive in batches that can be minutes old: those keep their own time, so they aren't shown
     * as live. A fresh one gets server time, like live readings.
     */
    suspend fun uploadMeasured(session: Session, bpm: Int, measuredAt: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - measuredAt < LIVE_THRESHOLD_MS) return upload(session, bpm, force = true)
        return try {
            withTimeoutOrNull(SAMPLE_TIMEOUT_MS) {
                uploadSample(session, bpm, measuredAt, Sources.WATCH)
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed", e)
            false
        }
    }

    private fun beat(session: Session) = couple(session.coupleId).child("beats").child(session.role)

    // ---- Observing -----------------------------------------------------------------------

    /** Seat → name for everyone who has joined; updates when the partner joins. */
    fun observeMembers(coupleId: String): Flow<Map<String, String>> =
        valueFlow(couple(coupleId).child("members")) { snapshot ->
            Roles.all.mapNotNull { role ->
                snapshot.child(role).child("name").getValue(String::class.java)?.let { role to it }
            }.toMap()
        }

    /** Latest reading for [role], or null if none has been written yet. */
    fun observeBeat(coupleId: String, role: String): Flow<Reading?> =
        valueFlow(couple(coupleId).child("beats").child(role)) { snapshot ->
            val bpm = snapshot.child("bpm").getValue(Int::class.java)
            val ts = snapshot.child("ts").getValue(Long::class.java)
            val source = snapshot.child("source").getValue(String::class.java)
            if (bpm != null && ts != null) Reading(bpm, ts, source) else null
        }

    /** The unused partner invite, or null once the partner has joined. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePendingInvite(coupleId: String): Flow<PendingInvite?> =
        valueFlow(couple(coupleId).child("pendingInvite")) { it.getValue(String::class.java) }
            .flatMapLatest { code ->
                if (code == null) {
                    flowOf(null)
                } else {
                    // A missing invite node means it expired and was cleaned up.
                    valueFlow(invite(code)) { snap ->
                        PendingInvite(code, snap.child("expires").getValue(Long::class.java) ?: 0L)
                    }
                }
            }

    /**
     * Estimated difference between Firebase server time and this device's clock, in ms.
     * Add it to [System.currentTimeMillis] before comparing against server timestamps.
     */
    fun serverTimeOffset(): Flow<Long> =
        valueFlow(database.getReference(".info/serverTimeOffset")) { snap ->
            (snap.getValue(Long::class.java) ?: 0L).also { knownOffset = it }
        }

    @Volatile
    private var knownOffset: Long? = null

    /**
     * This device's clock corrected to Firebase server time, as far as it's known. Use it for
     * timestamps written by the client and when comparing them, so a phone whose clock is off
     * neither has its writes refused nor misjudges how old something is.
     */
    suspend fun serverNow(): Long {
        val offset = knownOffset ?: withTimeoutOrNull(OFFSET_WAIT_MS) { serverTimeOffset().first() } ?: 0L
        return System.currentTimeMillis() + offset
    }

    // ---- Presence, messages, buzzes ------------------------------------------------------

    private fun presence(coupleId: String, role: String) = couple(coupleId).child("presence").child(role)

    /**
     * While collected, keeps this person marked as connected. Firebase's onDisconnect flips
     * `connected` off server-side if the connection drops (dead battery, no signal), within about a minute.
     */
    fun maintainPresence(session: Session, timeZone: String): Flow<Boolean> =
        valueFlow(database.getReference(".info/connected")) { it.getValue(Boolean::class.java) == true }
            .onEach { connected ->
                if (!connected) return@onEach
                val ref = presence(session.coupleId, session.role)
                try {
                    ref.onDisconnect()
                        .updateChildren(mapOf("connected" to false, "lastSeen" to ServerValue.TIMESTAMP))
                        .await()
                    ref.updateChildren(
                        mapOf("connected" to true, "tz" to timeZone, "lastSeen" to ServerValue.TIMESTAMP),
                    ).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't update presence", e)
                }
            }

    /**
     * Records that this person just used their phone or watch. [known] is their presence if the
     * caller already listens to it, which saves a round trip to the database every minute.
     */
    suspend fun markActive(session: Session, known: Presence? = null) {
        ensureSignedIn()
        val ref = presence(session.coupleId, session.role)
        val updates = mutableMapOf<String, Any>("lastActive" to ServerValue.TIMESTAMP, "lastSeen" to ServerValue.TIMESTAMP)
        // While the watch says asleep, note when each stretch of phone use starts, so the partner
        // sees "woke up" and then "asleep" again, or awake if it goes on (see sleepState). The
        // watch's sleep flag is left alone: it only reports changes, so it can't be cleared here.
        val current = known ?: runCatching { ref.get().await() }.getOrNull()?.let { snap ->
            Presence(
                asleep = snap.child("asleep").getValue(Boolean::class.java) == true,
                asleepAt = snap.child("asleepAt").getValue(Long::class.java),
                lastActive = snap.child("lastActive").getValue(Long::class.java),
                wokeAt = snap.child("wokeAt").getValue(Long::class.java),
            )
        }
        current?.run {
            // lastActive is server time, so compare it with server time.
            val newStretch = lastActive == null || toServerTime(System.currentTimeMillis()) - lastActive > StatusRules.INACTIVE_AFTER_MS
            val stretchFromBeforeSleep = asleepAt != null && (wokeAt == null || wokeAt < asleepAt)
            if (asleep && (newStretch || stretchFromBeforeSleep)) updates["wokeAt"] = ServerValue.TIMESTAMP
        }
        ref.updateChildren(updates).await()
    }

    /** Records that this person is on a call. Kept apart from lastActive: a call isn't use (see [ActivityModel]). */
    suspend fun markOnCall(session: Session) {
        ensureSignedIn()
        presence(session.coupleId, session.role)
            .updateChildren(mapOf("callAt" to ServerValue.TIMESTAMP, "lastSeen" to ServerValue.TIMESTAMP))
            .await()
    }

    /** Records that this person is on the move with their phone (see [ActivityModel]). */
    suspend fun markMoving(session: Session) {
        ensureSignedIn()
        presence(session.coupleId, session.role)
            .updateChildren(mapOf("movingAt" to ServerValue.TIMESTAMP, "lastSeen" to ServerValue.TIMESTAMP))
            .await()
    }

    /** Publishes this person's sleep verdict, decided by their own phone (see [SleepModel]). */
    suspend fun publishSleep(session: Session, decision: SleepDecision) {
        ensureSignedIn()
        val value = buildMap<String, Any> {
            put("state", decision.state.key)
            put("reason", decision.reason.key)
            put("at", ServerValue.TIMESTAMP)
            decision.since?.let { put("since", it) }
            decision.basis?.let { put("basis", it) }
            decision.lastUse?.let { put("lastUse", it) }
        }
        presence(session.coupleId, session.role).child("sleep").setValue(value).await()
    }

    /** This device's clock time [at] in server time, using the offset if it's known yet. */
    fun toServerTime(at: Long): Long = at + (knownOffset ?: 0L)

    /** Server time [at] on this device's clock: the reverse of [toServerTime]. */
    fun toDeviceTime(at: Long): Long = at - (knownOffset ?: 0L)

    /** Called by the watch when Wear OS detects sleep starting or ending. */
    /** [at] is when the change happened (a report can be sent late); server time if null. */
    suspend fun setAsleep(session: Session, asleep: Boolean, at: Long? = null) {
        ensureSignedIn()
        // In server time, like lastActive and wokeAt, which it's compared with.
        val offset = knownOffset ?: withTimeoutOrNull(OFFSET_WAIT_MS) { serverTimeOffset().first() } ?: 0L
        val changedAt: Any = at?.let { (it + offset).coerceAtMost(System.currentTimeMillis() + offset) } ?: ServerValue.TIMESTAMP
        presence(session.coupleId, session.role)
            .updateChildren(
                mapOf("asleep" to asleep, "asleepAt" to changedAt, "lastSeen" to ServerValue.TIMESTAMP),
            )
            .await()
    }

    fun observePresence(coupleId: String, role: String): Flow<Presence?> =
        valueFlow(presence(coupleId, role)) { snap ->
            if (!snap.exists()) {
                null
            } else {
                Presence(
                    connected = snap.child("connected").getValue(Boolean::class.java) == true,
                    lastSeen = snap.child("lastSeen").getValue(Long::class.java),
                    lastActive = snap.child("lastActive").getValue(Long::class.java),
                    movingAt = snap.child("movingAt").getValue(Long::class.java),
                    callAt = snap.child("callAt").getValue(Long::class.java),
                    asleep = snap.child("asleep").getValue(Boolean::class.java) == true,
                    asleepAt = snap.child("asleepAt").getValue(Long::class.java),
                    wokeAt = snap.child("wokeAt").getValue(Long::class.java),
                    sleep = parseSleep(snap.child("sleep")),
                    timeZone = snap.child("tz").getValue(String::class.java),
                    phoneBattery = parseBattery(snap.child("battery").child(Devices.PHONE)),
                    watchBattery = parseBattery(snap.child("battery").child(Devices.WATCH)),
                    checkIn = parseCheckIn(snap.child("checkIn")),
                    quietHours = parseQuietHours(snap.child("quiet")),
                    watch = snap.child("watch").takeIf { it.exists() }?.let { w ->
                        WatchLink(
                            connected = w.child("connected").getValue(Boolean::class.java) == true,
                            since = w.child("ts").getValue(Long::class.java),
                        )
                    },
                )
            }
        }

    private fun parseSleep(snap: DataSnapshot): SleepDecision? {
        if (!snap.exists()) return null
        val state = SleepState.fromKey(snap.child("state").getValue(String::class.java)) ?: return null
        return SleepDecision(
            state = state,
            since = snap.child("since").getValue(Long::class.java),
            basis = snap.child("basis").getValue(Long::class.java),
            lastUse = snap.child("lastUse").getValue(Long::class.java),
            reason = SleepReason.fromKey(snap.child("reason").getValue(String::class.java)),
            decidedAt = snap.child("at").getValue(Long::class.java),
        )
    }

    /** Uploads this device's battery for the partner to see. [device] is [Devices.PHONE] or [Devices.WATCH]. */
    suspend fun reportBattery(session: Session, device: String, state: BatteryState) {
        ensureSignedIn()
        presence(session.coupleId, session.role).child("battery").child(device)
            .setValue(mapOf("level" to state.level, "charging" to state.charging, "ts" to ServerValue.TIMESTAMP))
            .await()
    }

    /** Tells the partner whether this phone can currently reach its watch. */
    suspend fun reportWatchLink(session: Session, connected: Boolean) {
        ensureSignedIn()
        presence(session.coupleId, session.role).child("watch")
            .setValue(mapOf("connected" to connected, "ts" to ServerValue.TIMESTAMP))
            .await()
    }

    /** Shares a check-in such as [CheckIns.HOME] on this person's card. */
    suspend fun checkIn(session: Session, text: String) {
        ensureSignedIn()
        presence(session.coupleId, session.role).child("checkIn")
            .setValue(mapOf("text" to text.take(CheckIns.MAX_LENGTH), "ts" to ServerValue.TIMESTAMP))
            .await()
    }

    /** Publishes this person's quiet hours so the partner can see when things will arrive silently. */
    suspend fun reportQuietHours(session: Session, quiet: QuietHours) {
        ensureSignedIn()
        presence(session.coupleId, session.role).child("quiet").setValue(
            mapOf(
                "enabled" to quiet.enabled,
                "start" to quiet.startMinute,
                "end" to quiet.endMinute,
                "allowWhenOnline" to quiet.allowWhenOnline,
            ),
        ).await()
    }

    /** Sets (or with null, clears) the date the couple will next be together. */
    suspend fun setCountdown(session: Session, date: LocalDate?) {
        ensureSignedIn()
        val ref = couple(session.coupleId).child("countdown")
        if (date == null) ref.removeValue().await() else ref.setValue(mapOf("date" to date.toString())).await()
    }

    fun observeCountdown(coupleId: String): Flow<LocalDate?> =
        valueFlow(couple(coupleId).child("countdown").child("date")) { snap ->
            snap.getValue(String::class.java)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        }

    private fun parseCheckIn(snap: DataSnapshot): CheckIn? {
        val text = snap.child("text").getValue(String::class.java) ?: return null
        val ts = snap.child("ts").getValue(Long::class.java) ?: return null
        return CheckIn(text, ts)
    }

    private fun parseQuietHours(snap: DataSnapshot): QuietHours? {
        if (!snap.exists()) return null
        val defaults = QuietHours()
        return QuietHours(
            enabled = snap.child("enabled").getValue(Boolean::class.java) ?: defaults.enabled,
            startMinute = snap.child("start").getValue(Int::class.java) ?: defaults.startMinute,
            endMinute = snap.child("end").getValue(Int::class.java) ?: defaults.endMinute,
            allowWhenOnline = snap.child("allowWhenOnline").getValue(Boolean::class.java) ?: defaults.allowWhenOnline,
        )
    }

    private fun parseBattery(snap: DataSnapshot): BatteryState? {
        val level = snap.child("level").getValue(Int::class.java) ?: return null
        return BatteryState(
            level = level,
            charging = snap.child("charging").getValue(Boolean::class.java) == true,
            updatedAt = snap.child("ts").getValue(Long::class.java),
        )
    }

    /** Drops a short message in the partner's inbox. Their phone shows it as a notification. */
    suspend fun sendMessage(session: Session, text: String, fromName: String?) {
        ensureSignedIn()
        val value = buildMap {
            put("text", text)
            put("ts", ServerValue.TIMESTAMP)
            fromName?.let { put("from", it) }
        }
        couple(session.coupleId).child("messages").child(session.partnerRole).push().setValue(value).await()
    }

    /** Messages waiting for this person, oldest first. Delete each with [deleteMessage] once shown. */
    fun observeInbox(session: Session): Flow<List<Message>> =
        valueFlow(couple(session.coupleId).child("messages").child(session.role)) { snap ->
            snap.children.mapNotNull { child ->
                val id = child.key ?: return@mapNotNull null
                val text = child.child("text").getValue(String::class.java) ?: return@mapNotNull null
                Message(
                    id = id,
                    text = text,
                    fromName = child.child("from").getValue(String::class.java),
                    timestamp = child.child("ts").getValue(Long::class.java) ?: 0L,
                )
            }.sortedBy { it.timestamp }
        }

    suspend fun deleteMessage(session: Session, messageId: String) {
        couple(session.coupleId).child("messages").child(session.role).child(messageId).removeValue().await()
    }

    /**
     * Makes the partner's watch (or phone) vibrate with [bpm], this person's last known heart rate.
     * Stamped with when it was sent (server-corrected), not when it reached the server: a buzz
     * queued offline and delivered much later would otherwise vibrate as if it were new. The
     * receiver shows a late one silently instead.
     */
    suspend fun sendBuzz(session: Session, bpm: Int, fromName: String?) {
        ensureSignedIn()
        val value = buildMap {
            put("bpm", bpm.coerceIn(BUZZ_MIN_BPM, BUZZ_MAX_BPM))
            put("ts", serverNow())
            fromName?.let { put("from", it) }
        }
        couple(session.coupleId).child("buzz").child(session.partnerRole).setValue(value).await()
    }

    /** The latest buzz sent to this person. */
    fun observeBuzz(session: Session): Flow<Buzz?> =
        valueFlow(couple(session.coupleId).child("buzz").child(session.role)) { snap ->
            val bpm = snap.child("bpm").getValue(Int::class.java)
            val ts = snap.child("ts").getValue(Long::class.java)
            if (bpm != null && ts != null) Buzz(bpm, ts, snap.child("from").getValue(String::class.java)) else null
        }

    /** Tells the partner's devices that someone is looking at their heart right now. */
    suspend fun markWatching(session: Session) {
        ensureSignedIn()
        couple(session.coupleId).child("watching").child(session.partnerRole).setValue(ServerValue.TIMESTAMP).await()
    }

    /** When the partner last looked at this person's heart (server time). */
    fun observeWatching(session: Session): Flow<Long?> =
        valueFlow(couple(session.coupleId).child("watching").child(session.role)) { it.getValue(Long::class.java) }

    // ---- Helpers -------------------------------------------------------------------------

    /**
     * Runs a send, giving up waiting after [SEND_TIMEOUT_MS]. Offline, Firebase holds a write
     * until it reconnects instead of failing, which left "Send" with no answer at all. The write
     * stays queued, so [SendResult.Queued] means "will go when back online" (while the app runs).
     */
    suspend fun trySend(block: suspend () -> Unit): SendResult = try {
        if (withTimeoutOrNull(SEND_TIMEOUT_MS) { block() } == null) SendResult.Queued else SendResult.Sent
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Send failed", e)
        SendResult.Failed
    }

    private fun inviteValue(coupleId: String, role: String, ttlMs: Long, fromName: String?, seatName: String?) =
        buildMap {
            put("couple", coupleId)
            put("role", role)
            put("expires", System.currentTimeMillis() + ttlMs)
            fromName?.let { put("from", it) }
            seatName?.let { put("name", it) }
        }

    /** Runs [write] with new codes until one isn't already taken (the rules reject reused codes). */
    private suspend fun withFreshCode(write: suspend (String) -> Unit): String {
        var lastError: Exception? = null
        repeat(CODE_ATTEMPTS) {
            val code = InviteCode.generate()
            try {
                write(code)
                return code
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Couldn't create an invite code")
    }

    /**
     * Never throws to collectors: screens collect these directly, and an exception there closes
     * the app. Sign-in is retried until it works (e.g. a watch with no connection yet). A refused
     * listener flags [accessDenied] and then listens again after a pause: a refusal can be
     * temporary (signing in, a token refresh), and a listener that just stopped left the screen
     * showing old data until the app was restarted.
     */
    private fun <T> valueFlow(ref: DatabaseReference, map: (DataSnapshot) -> T): Flow<T> = flow {
        var retryMs = LISTEN_RETRY_MIN_MS
        while (true) {
            var received = false
            emitAll(listenOnce(ref, map).onEach { received = true })
            if (received) retryMs = LISTEN_RETRY_MIN_MS
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(LISTEN_RETRY_MAX_MS)
        }
    }.conflate()

    /** One listener on [ref]; completes if the database cancels it. */
    private fun <T> listenOnce(ref: DatabaseReference, map: (DataSnapshot) -> T): Flow<T> = callbackFlow {
        while (true) {
            try {
                ensureSignedIn()
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sign-in failed; retrying", e)
                delay(SIGN_IN_RETRY_MS)
            }
        }
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                runCatching { map(snapshot) }
                    .onSuccess { trySend(it) }
                    .onFailure { Log.w(TAG, "Couldn't read ${ref.path}", it) }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Listener on ${ref.path} cancelled: ${error.message}")
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    lastDenied = ref.path.toString()
                    _accessDenied.value = true
                }
                close()
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }
}

/** Outcome of [HeartRepository.trySend]. */
enum class SendResult { Sent, Queued, Failed }
