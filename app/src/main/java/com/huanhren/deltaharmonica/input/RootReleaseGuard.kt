package com.huanhren.deltaharmonica.input

import android.content.Context
import android.provider.Settings

internal data class RootReleaseRecord(
    val sessionId: String?,
    val helper: RootHelperIdentity?,
    val createdAtMs: Long? = null,
    val dispatcherId: String? = null,
)

internal interface RootReleaseJournal {
    fun pending(): RootReleaseRecord?
    fun begin(record: RootReleaseRecord): Boolean
    fun confirm(record: RootReleaseRecord): Boolean
}

/** Durable proof requirement. Includes the rc.3 boot-only marker for in-place upgrades. */
internal class RootReleaseGuard(context: Context) : RootReleaseJournal {
    private val preferences = context.getSharedPreferences("root_touch_release", Context.MODE_PRIVATE)
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    override fun pending(): RootReleaseRecord? {
        val values = preferences.all
        if (BOOT !in values) return if (SESSION in values) invalidRecord else null
        val recordedBoot = values[BOOT] as? Int ?: return invalidRecord
        if (boot >= 0 && recordedBoot >= 0 && boot > recordedBoot) return null
        if (SESSION !in values) return RootReleaseRecord(null, null)
        val session = values[SESSION] as? String ?: return invalidRecord
        val pid = (values[PID] as? Int)?.takeIf { it > 1 } ?: return RootReleaseRecord(session, null)
        val start = (values[START] as? Long)?.takeIf { it > 0 } ?: return RootReleaseRecord(session, null)
        return RootReleaseRecord(session, RootHelperIdentity(pid, start), values[CREATED] as? Long, values[DISPATCHER] as? String)
    }

    override fun begin(record: RootReleaseRecord): Boolean {
        val helper = record.helper ?: return false
        if (pending() != null) return false
        return preferences.edit().putInt(BOOT, boot).putString(SESSION, record.sessionId)
            .putInt(PID, helper.pid).putLong(START, helper.startTicks)
            .putLong(CREATED, record.createdAtMs ?: 0)
            .putString(DISPATCHER, record.dispatcherId)
            .commit()
    }

    override fun confirm(record: RootReleaseRecord): Boolean {
        val current = pending()
        if (current != null && current != record) return false
        return preferences.edit().remove(BOOT).remove(SESSION).remove(PID).remove(START).remove(CREATED)
            .remove(DISPATCHER).commit()
    }

    private companion object {
        const val BOOT = "session_boot"
        const val SESSION = "session_id"
        const val PID = "helper_pid"
        const val START = "helper_start_ticks"
        const val CREATED = "session_created_ms"
        const val DISPATCHER = "dispatcher_id"
        val invalidRecord = RootReleaseRecord("", null)
    }
}
