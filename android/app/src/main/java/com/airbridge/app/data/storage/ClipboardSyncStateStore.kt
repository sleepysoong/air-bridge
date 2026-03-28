package com.airbridge.app.data.storage

import android.content.Context

class ClipboardSyncStateStore(
    context: Context,
) {
    private val sharedPreferences = context.getSharedPreferences("air_bridge_clipboard_sync_state", Context.MODE_PRIVATE)

    fun readLastSentFingerprint(): String? = sharedPreferences.getString(KEY_LAST_SENT_FINGERPRINT, null)

    fun writeLastSentFingerprint(value: String?) {
        sharedPreferences.edit().putString(KEY_LAST_SENT_FINGERPRINT, value).apply()
    }

    fun readLastAppliedFingerprint(): String? = sharedPreferences.getString(KEY_LAST_APPLIED_FINGERPRINT, null)

    fun writeLastAppliedFingerprint(value: String?) {
        sharedPreferences.edit().putString(KEY_LAST_APPLIED_FINGERPRINT, value).apply()
    }

    fun clear() {
        sharedPreferences.edit()
            .remove(KEY_LAST_SENT_FINGERPRINT)
            .remove(KEY_LAST_APPLIED_FINGERPRINT)
            .apply()
    }

    companion object {
        private const val KEY_LAST_SENT_FINGERPRINT = "last_sent_fingerprint"
        private const val KEY_LAST_APPLIED_FINGERPRINT = "last_applied_fingerprint"
    }
}
