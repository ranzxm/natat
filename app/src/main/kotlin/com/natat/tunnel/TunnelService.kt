package com.natat.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

class TunnelService : VpnService() {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var monitor: ScheduledFuture<*>? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var configFile: File? = null
    private var sshProxy: SshSocksProxy? = null
    private var currentConfig = TunnelConfig()
    private var retry = 0
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopped = true
            executor.execute {
                stopTunnel("Dihentikan")
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        currentConfig = intent?.getStringExtra(EXTRA_CONFIG)?.let {
            runCatching { TunnelConfig.fromJson(it) }.getOrDefault(TunnelConfig())
        } ?: ConfigStore.load(this)
        stopped = false
        startForeground(NOTIFICATION_ID, notification("Menghubungkan..."))
        executor.execute { startTunnel() }
        return START_STICKY
    }

    private fun startTunnel() {
        if (stopped) return
        stopTunnel("Menyambungkan ulang")
        try {
            val endpoint = when (currentConfig.protocol) {
                TunnelProtocol.SOCKS5 -> LocalSocksEndpoint(currentConfig.host, currentConfig.port, currentConfig.password)
                TunnelProtocol.SSH -> {
                    val proxy = SshSocksProxy(currentConfig)
                    sshProxy = proxy
                    val localEndpoint = proxy.start()
                    proxy.learnedHostKeyFingerprint?.let { fingerprint ->
                        currentConfig = currentConfig.copy(sshHostKeyFingerprint = fingerprint)
                        ConfigStore.save(this, currentConfig)
                    }
                    localEndpoint
                }
                else -> error("Transport ${currentConfig.protocol} belum tersedia")
            }
            val yaml = File(filesDir, "natat-tunnel.yml")
            yaml.writeText(currentConfig.toNativeYaml(endpoint))
            val interfaceBuilder = Builder()
                .setSession(currentConfig.name)
                .setMtu(1500)
                .addAddress("198.18.0.1", 15)
                .addAddress("fc00::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDisallowedApplication(packageName)
            currentConfig.dnsServers.forEach { interfaceBuilder.addDnsServer(it) }
            vpnInterface = interfaceBuilder.establish() ?: error("VPN interface gagal dibuat")
            configFile = yaml
            val descriptor = vpnInterface ?: error("VPN descriptor tidak tersedia")
            check(NativeTunnel.start(yaml, descriptor.fd)) { "Native tunnel gagal dimulai" }
            retry = 0
            monitor?.cancel(false)
            monitor = executor.scheduleWithFixedDelay({ monitorTunnel() }, 2, 5, TimeUnit.SECONDS)
            updateNotification("Terhubung via ${currentConfig.protocol}")
            broadcast(STATE_CONNECTED)
        } catch (error: Exception) {
            fail(error.message ?: "Koneksi gagal")
        }
    }

    private fun monitorTunnel() {
        if (stopped || !NativeTunnel.isRunning()) {
            if (!stopped) fail("Tunnel berhenti, mencoba ulang")
            return
        }
        NativeTunnel.stats().takeIf { it.size == 4 }?.let { stats ->
            broadcast(STATE_STATS, txBytes = stats[1], rxBytes = stats[3])
        }
    }

    private fun fail(detail: String, reconnect: Boolean = true) {
        stopTunnel(detail)
        retry++
        updateNotification("Gagal, mencoba ulang")
        broadcast(STATE_ERROR, detail)
        if (reconnect && currentConfig.autoReconnect && !stopped) {
            val delay = min(60L, 1L shl min(retry, 6))
            executor.schedule({ startTunnel() }, delay, TimeUnit.SECONDS)
        } else {
            stopSelf()
        }
    }

    private fun stopTunnel(reason: String) {
        monitor?.cancel(false)
        monitor = null
        runCatching { NativeTunnel.stop() }
        runCatching { sshProxy?.close() }
        sshProxy = null
        vpnInterface?.close()
        vpnInterface = null
        configFile?.delete()
        configFile = null
        broadcast(STATE_DISCONNECTED, reason)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Natat Tunnel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(!stopped)
            .addAction(Notification.Action.Builder(null, "Putus", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tunnel connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun broadcast(state: String, detail: String = "", txBytes: Long = 0, rxBytes: Long = 0) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_DETAIL, detail)
            .putExtra(EXTRA_TX_BYTES, txBytes)
            .putExtra(EXTRA_RX_BYTES, rxBytes))
    }

    override fun onDestroy() {
        stopped = true
        executor.execute {
            stopTunnel("Service berhenti")
            executor.shutdown()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else null
    }

    companion object {
        const val ACTION_STOP = "com.natat.tunnel.STOP"
        const val ACTION_STATE = "com.natat.tunnel.STATE"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_RX_BYTES = "rx_bytes"
        const val STATE_CONNECTED = "connected"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_ERROR = "error"
        const val STATE_STATS = "stats"
        private const val CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1001
    }
}

private fun TunnelConfig.toNativeYaml(endpoint: LocalSocksEndpoint): String = """
tunnel:
  name: natat
  mtu: 1500
  ipv4: 198.18.0.1
  ipv6: 'fc00::1'
  icmp: 'off'
socks5:
  address: '${endpoint.host.yamlValue()}'
  port: ${endpoint.port}
  udp: '${if (protocol == TunnelProtocol.SSH || !udpEnabled) "tcp" else "udp"}'
${if (protocol == TunnelProtocol.SOCKS5 && username.isNotBlank()) "  username: '${username.yamlValue()}'\n" else ""}${if (protocol == TunnelProtocol.SOCKS5 && endpoint.password.isNotBlank()) "  password: '${endpoint.password.yamlValue()}'\n" else ""}${if (protocol == TunnelProtocol.SSH) "  username: 'natat'\n  password: '${endpoint.password.yamlValue()}'\n" else ""}misc:
  connect-timeout: $connectTimeoutMs
  log-level: error
""".trimIndent()

private fun String.yamlValue(): String = replace("'", "''")
