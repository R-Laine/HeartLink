package com.heartlink.shared

import android.content.Context

/** Persists which couple and seat this device belongs to. */
object Identity {
    private const val PREFS = "heartlink"
    // "couple_id" rather than "couple": sessions from older builds stored a share code here and must re-link.
    private const val KEY_COUPLE = "couple_id"
    private const val KEY_ROLE = "role"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): Session? {
        val prefs = prefs(ctx)
        val couple = prefs.getString(KEY_COUPLE, null) ?: return null
        val role = prefs.getString(KEY_ROLE, null)?.takeIf { it in Roles.all } ?: return null
        return Session(couple, role)
    }

    fun set(ctx: Context, session: Session) {
        prefs(ctx).edit()
            .putString(KEY_COUPLE, session.coupleId)
            .putString(KEY_ROLE, session.role)
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
