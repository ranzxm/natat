package com.natat.tunnel

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter

class MainActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var protocol: Spinner
    private lateinit var status: TextView
    private lateinit var connect: Button
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(TunnelService.EXTRA_DETAIL)
                ?.takeIf { it.isNotBlank() } ?: intent?.getStringExtra(TunnelService.EXTRA_STATE) ?: "-"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        loadConfig()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TunnelService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onPause() {
        unregisterReceiver(stateReceiver)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startTunnel(ConfigStore.load(this))
        }
    }

    private fun buildView(): View {
        val padding = (resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply { text = "Natat Tunnel"; textSize = 26f })
        root.addView(TextView(this).apply { text = "Lightweight private tunnel"; textSize = 14f })
        protocol = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, TunnelProtocol.entries.map { it.name })
        }
        root.addView(label("Protocol")); root.addView(protocol)
        host = field("Host / server"); root.addView(host)
        port = field("Port"); root.addView(port)
        username = field("Username (optional)"); root.addView(username)
        password = field("Password (optional)"); root.addView(password)
        status = TextView(this).apply { text = "Disconnected"; textSize = 15f; setPadding(0, padding, 0, padding) }
        root.addView(status)
        connect = Button(this).apply { text = "CONNECT"; setOnClickListener { startOrStop() } }
        root.addView(connect)
        return root
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        setPadding(0, 12, 0, 12)
    }

    private fun label(value: String) = TextView(this).apply { text = value; setPadding(0, 18, 0, 0) }

    private fun loadConfig() {
        val config = ConfigStore.load(this)
        host.setText(config.host); port.setText(config.port.toString())
        username.setText(config.username); password.setText(config.password)
        protocol.setSelection(TunnelProtocol.entries.indexOf(config.protocol).coerceAtLeast(0))
    }

    private fun startOrStop() {
        val serviceIntent = Intent(this, TunnelService::class.java)
        if (connect.text == "DISCONNECT") {
            serviceIntent.action = TunnelService.ACTION_STOP
            startService(serviceIntent)
            connect.text = "CONNECT"
            return
        }
        val config = TunnelConfig(
            protocol = TunnelProtocol.entries[protocol.selectedItemPosition], host = host.text.toString().trim(),
            port = port.text.toString().toIntOrNull() ?: 0, username = username.text.toString(), password = password.text.toString()
        )
        if (config.host.isBlank() || config.port !in 1..65535) { status.text = "Host dan port tidak valid"; return }
        ConfigStore.save(this, config)
        val permission = VpnService.prepare(this)
        if (permission != null) { startActivityForResult(permission, VPN_REQUEST); return }
        startTunnel(config)
    }

    private fun startTunnel(config: TunnelConfig) {
        val serviceIntent = Intent(this, TunnelService::class.java)
        serviceIntent.putExtra(TunnelService.EXTRA_CONFIG, config.toJson())
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        connect.text = "DISCONNECT"; status.text = "Connecting..."
    }

    companion object {
        private const val VPN_REQUEST = 10
    }
}
