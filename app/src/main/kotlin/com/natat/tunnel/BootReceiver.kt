package com.natat.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = ConfigStore.load(context)
        if (!config.startOnBoot || VpnService.prepare(context) != null) return
        val service = Intent(context, TunnelService::class.java).putExtra(TunnelService.EXTRA_CONFIG, config.toJson())
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
    }
}
