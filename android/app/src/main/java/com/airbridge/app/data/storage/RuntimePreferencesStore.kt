package com.airbridge.app.data.storage

import android.content.Context

class RuntimePreferencesStore(
    context: Context,
) {
    private val sharedPreferences = context.getSharedPreferences("air_bridge_runtime_preferences", Context.MODE_PRIVATE)

    fun isForegroundRuntimeEnabled(): Boolean =
        sharedPreferences.getBoolean(KEY_FOREGROUND_RUNTIME_ENABLED, false)

    fun setForegroundRuntimeEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_FOREGROUND_RUNTIME_ENABLED, enabled).apply()
    }

    companion object {
        private const val KEY_FOREGROUND_RUNTIME_ENABLED = "foreground_runtime_enabled"
    }
}
