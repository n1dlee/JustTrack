package com.justtrack.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PrefsKeys.AUTO_START, PrefsKeys.DEFAULT_AUTO_START)) {
            ContextCompat.startForegroundService(context, Intent(context, GestureService::class.java))
        }
    }
}
