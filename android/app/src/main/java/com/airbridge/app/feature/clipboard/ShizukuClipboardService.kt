package com.airbridge.app.feature.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.util.Log

class ShizukuClipboardService : IShizukuClipboard.Stub() {
    private val context: Context by lazy {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMainMethod = activityThreadClass.getMethod("systemMain")
        val activityThread = systemMainMethod.invoke(null)
        val getSystemContextMethod = activityThreadClass.getMethod("getSystemContext")
        getSystemContextMethod.invoke(activityThread) as Context
    }

    override fun getClipboard(): ClipData? {
        return try {
            if (Looper.myLooper() == null) {
                Looper.prepare()
            }
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.primaryClip
        } catch (e: Exception) {
            Log.e("ShizukuClipboardService", "Failed to read clipboard", e)
            null
        }
    }
}
