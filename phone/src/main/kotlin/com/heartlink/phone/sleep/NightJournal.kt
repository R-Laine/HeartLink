package com.heartlink.phone.sleep

import android.content.Context
import android.util.Log
import com.heartlink.shared.FinishedSleep
import com.heartlink.shared.NightRecord
import com.heartlink.shared.Presence
import com.heartlink.shared.SleepConfidence
import com.heartlink.shared.SleepLearning
import com.heartlink.shared.SleepModel
import com.heartlink.shared.SleepReason
import com.heartlink.shared.SleepTuning
import com.heartlink.shared.UseRun
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * This person's sleep learning, kept only on this phone: the nights checked so far, and the
 * [SleepTuning] learned from them. A night is checked once Health Connect or the Sleep API has
 * recorded it as finished and a few hours have passed (so the replay sees the morning's use);
 * then [SleepLearning] retunes. Nothing here is uploaded.
 *
 * Main thread only.
 */
object NightJournal {
    private const val TAG = "NightJournal"

    // In the session's prefs file, so unlinking clears it too. The nights are in a file (too big
    // for prefs), which [clear] deletes on unlinking.
    private const val PREFS = "heartlink"
    private const val KEY_ONSET = "journal_onset"
    private const val KEY_WATCH_WOKE = "journal_watch_woke"
    /** The last night checked, so a watch still saying asleep doesn't get the same night checked twice. */
    private const val KEY_CHECKED = "journal_checked"
    private const val KEY_TUNING = "sleep_tuning"
    private const val FILE = "sleep_nights.json"

    /** A night whose real wake time hasn't turned up within this long is given up on. */
    private val GIVE_UP_MS = TimeUnit.HOURS.toMillis(30)

    /** Longest believable sleep. */
    private val LONGEST_SLEEP_MS = TimeUnit.HOURS.toMillis(18)

    private var cachedTuning: SleepTuning? = null

    fun tuning(ctx: Context): SleepTuning =
        cachedTuning ?: loadTuning(ctx).also { cachedTuning = it }

    /** Follows the watch's reports: remembers when the current sleep started, and when the watch said it ended. */
    fun noteWatch(ctx: Context, presence: Presence?) {
        val p = prefs(ctx)
        val asleepAt = presence?.asleepAt ?: return
        val onset = p.getLong(KEY_ONSET, 0L)
        if (presence.asleep) {
            // A new sleep replaces one still waiting (e.g. a nap before last night was recorded).
            if (asleepAt != onset && asleepAt != p.getLong(KEY_CHECKED, 0L)) {
                p.edit().putLong(KEY_ONSET, asleepAt).remove(KEY_WATCH_WOKE).apply()
            }
        } else if (onset != 0L && asleepAt > onset && !p.contains(KEY_WATCH_WOKE)) {
            p.edit().putLong(KEY_WATCH_WOKE, asleepAt).apply()
        }
    }

    /** True while a night is waiting for its real wake time, so Health Connect is worth asking. */
    fun awaitingTruth(ctx: Context, now: Long): Boolean {
        val onset = prefs(ctx).getLong(KEY_ONSET, 0L)
        return onset != 0L && now - onset < GIVE_UP_MS
    }

    /**
     * Checks the waiting night if [finished] now says when it ended and the morning has passed.
     * Returns true if the tuning changed.
     */
    fun check(
        ctx: Context,
        finished: List<FinishedSleep>,
        uses: List<UseRun>,
        readings: List<SleepConfidence>,
        now: Long,
    ): Boolean {
        val p = prefs(ctx)
        val onset = p.getLong(KEY_ONSET, 0L).takeIf { it != 0L } ?: return false
        if (now - onset >= GIVE_UP_MS) {
            p.edit().remove(KEY_ONSET).remove(KEY_WATCH_WOKE).apply()
            return false
        }
        // The end of the first recorded night after falling asleep (sessions an app split around a
        // wake in the middle are joined first); Health Connect's record beats the Sleep API's.
        val ends = finished.groupBy { it.source }.values.flatMap { SleepModel.mergeSleeps(it) }
            .filter { it.end > onset && it.end <= now && it.end - onset <= LONGEST_SLEEP_MS }
        val truth = ends.filter { it.source == SleepReason.HealthConnect }.minByOrNull { it.end }
            ?: ends.minByOrNull { it.end }
            ?: return false
        // Wait until the replay can see the whole morning.
        if (now < truth.end + SleepLearning.AFTER_WAKE_MS) return false

        val until = truth.end + SleepLearning.AFTER_WAKE_MS
        val night = NightRecord(
            onset = onset,
            wokeAt = truth.end,
            truthSource = truth.source,
            uses = uses.filter { it.end > onset && it.start <= until },
            readings = readings.filter { it.at > onset && it.at <= until },
            watchWokeAt = p.getLong(KEY_WATCH_WOKE, 0L).takeIf { it != 0L },
        )
        val nights = (loadNights(ctx).filter { it.onset != onset } + night).sortedBy { it.onset }.takeLast(SleepLearning.MAX_NIGHTS)
        saveNights(ctx, nights)
        p.edit().remove(KEY_ONSET).remove(KEY_WATCH_WOKE).putLong(KEY_CHECKED, onset).apply()

        val before = tuning(ctx)
        val after = SleepLearning.tune(before, nights)
        Log.d(TAG, "Checked night: woke ${night.wokeAt}, off by ${SleepLearning.error(night, before) / 60_000} min; $before -> $after")
        if (after == before) return false
        saveTuning(ctx, after)
        return true
    }

    data class Summary(val nights: Int, val averageError: Long?, val defaultError: Long?)

    fun summary(ctx: Context): Summary {
        val nights = loadNights(ctx)
        return Summary(
            nights = nights.size,
            averageError = SleepLearning.averageError(nights, tuning(ctx)),
            defaultError = SleepLearning.averageError(nights, SleepTuning.DEFAULT),
        )
    }

    /** Forgets everything learned (Settings → Reset, or unlinking). */
    fun clear(ctx: Context) {
        File(ctx.noBackupFilesDir, FILE).delete()
        prefs(ctx).edit().remove(KEY_TUNING).remove(KEY_ONSET).remove(KEY_WATCH_WOKE).remove(KEY_CHECKED).apply()
        cachedTuning = null
    }

    // ---- Storage ------------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadTuning(ctx: Context): SleepTuning {
        val text = prefs(ctx).getString(KEY_TUNING, null) ?: return SleepTuning.DEFAULT
        return runCatching {
            val o = JSONObject(text)
            SleepTuning(
                confirmMs = o.getLong("confirm"),
                spanMs = o.getLong("span"),
                restedConfirmMs = o.getLong("restedConfirm"),
                restedSpanMs = o.getLong("restedSpan"),
            )
        }.getOrDefault(SleepTuning.DEFAULT)
    }

    private fun saveTuning(ctx: Context, tuning: SleepTuning) {
        val o = JSONObject()
            .put("confirm", tuning.confirmMs)
            .put("span", tuning.spanMs)
            .put("restedConfirm", tuning.restedConfirmMs)
            .put("restedSpan", tuning.restedSpanMs)
        prefs(ctx).edit().putString(KEY_TUNING, o.toString()).apply()
        cachedTuning = tuning
    }

    private fun loadNights(ctx: Context): List<NightRecord> {
        val file = File(ctx.noBackupFilesDir, FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val uses = o.getJSONArray("uses")
                val readings = o.getJSONArray("readings")
                NightRecord(
                    onset = o.getLong("onset"),
                    wokeAt = o.getLong("wokeAt"),
                    truthSource = SleepReason.fromKey(o.optString("source")),
                    uses = (0 until uses.length()).map { j -> uses.getJSONArray(j).let { UseRun(it.getLong(0), it.getLong(1)) } },
                    readings = (0 until readings.length()).map { j -> readings.getJSONArray(j).let { SleepConfidence(it.getLong(0), it.getInt(1)) } },
                    watchWokeAt = if (o.has("watchWokeAt")) o.getLong("watchWokeAt") else null,
                )
            }
        }.onFailure { Log.w(TAG, "Couldn't read nights; starting again", it) }.getOrDefault(emptyList())
    }

    private fun saveNights(ctx: Context, nights: List<NightRecord>) {
        val array = JSONArray()
        nights.forEach { night ->
            array.put(
                JSONObject()
                    .put("onset", night.onset)
                    .put("wokeAt", night.wokeAt)
                    .put("source", night.truthSource.key)
                    .put("uses", JSONArray().apply { night.uses.forEach { put(JSONArray().put(it.start).put(it.end)) } })
                    .put("readings", JSONArray().apply { night.readings.forEach { put(JSONArray().put(it.at).put(it.confidence)) } })
                    .apply { night.watchWokeAt?.let { put("watchWokeAt", it) } },
            )
        }
        runCatching { File(ctx.noBackupFilesDir, FILE).writeText(array.toString()) }
            .onFailure { Log.w(TAG, "Couldn't save nights", it) }
    }
}
