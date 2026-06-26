package com.gguf.zerocopy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.domain.server.ModelServerService

/**
 * Starts the local inference server on device boot when the user has
 * enabled "Auto-start on boot" in Settings.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SettingsManager.init(context)
        if (!SettingsManager.serverEnabled) return
        val svcIntent = Intent(context, ModelServerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
    }
}
