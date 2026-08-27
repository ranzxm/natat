package com.natat.tunnel

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private val background = Color.rgb(9, 16, 29)
    private val cardBackground = Color.rgb(18, 29, 48)
    private val fieldBackground = Color.rgb(24, 39, 63)
    private val primary = Color.rgb(73, 132, 255)
    private val textPrimary = Color.rgb(239, 244, 255)
    private val textSecondary = Color.rgb(153, 169, 196)
    private val green = Color.rgb(65, 211, 145)
    private val red = Color.rgb(255, 107, 107)

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var protocol: Spinner
    private lateinit var autoReconnect: Switch
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var connectButton: Button
    private lateinit var logView: TextView
    private var tunnelRequested = false
    private var pendingExport: String? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(TunnelService.EXTRA_STATE).orEmpty()
            val detail = intent?.getStringExtra(TunnelService.EXTRA_DETAIL).orEmpty()
            when (state) {
                TunnelService.STATE_CONNECTED -> setConnected(detail.ifBlank { "SOCKS5 relay active" })
                TunnelService.STATE_ERROR -> setRetrying(detail)
                TunnelService.STATE_DISCONNECTED -> {
                    if (!tunnelRequested) setDisconnected(detail)
                    appendLog(detail.ifBlank { "Disconnected" })
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        setContentView(buildView())
        applyConfig(ConfigStore.load(this))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TunnelService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(stateReceiver, filter)
    }

    override fun onPause() {
        unregisterReceiver(stateReceiver)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startTunnel(ConfigStore.load(this))
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            IMPORT_REQUEST -> importConfig(data.data!!)
            EXPORT_REQUEST -> exportConfig(data.data!!)
        }
    }

    private fun buildView(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(background); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label("NATAT", 25f, textPrimary, true))
        heading.addView(label("LIGHTWEIGHT TUNNEL CLIENT", 10f, textSecondary, true))
        header.addView(heading, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(label("v0.1", 11f, textSecondary, false))
        root.addView(header)
        root.addView(space(22))

        val statusCard = card()
        val statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { background = circle(green) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusRow.addView(label("  STATUS", 11f, textSecondary, true))
        statusRow.addView(label("READY", 11f, green, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
        statusTitle = label("Disconnected", 24f, textPrimary, true)
        statusDetail = label("No active tunnel", 13f, textSecondary, false)
        statusCard.addView(statusRow)
        statusCard.addView(space(18))
        statusCard.addView(statusTitle)
        statusCard.addView(statusDetail)
        statusCard.addView(space(16))
        connectButton = button("CONNECT", primary).apply { setOnClickListener { startOrStop() } }
        statusCard.addView(connectButton, LinearLayout.LayoutParams(MATCH, dp(48)))
        root.addView(statusCard)

        root.addView(space(14))
        val configCard = card()
        configCard.addView(sectionTitle("CONNECTION CONFIG"))
        configCard.addView(label("Protocol", 12f, textSecondary, false))
        protocol = Spinner(this).apply {
            background = rounded(fieldBackground, 10)
            adapter = protocolAdapter()
        }
        configCard.addView(protocol, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(6) })
        configCard.addView(space(12))

        val addressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        host = field("Server host")
        port = field("Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        addressRow.addView(host, LinearLayout.LayoutParams(0, dp(52), 2f))
        addressRow.addView(port, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        configCard.addView(addressRow)
        configCard.addView(space(8))
        val authRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        username = field("Username")
        password = field("Password").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        authRow.addView(username, LinearLayout.LayoutParams(0, dp(52), 1f))
        authRow.addView(password, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        configCard.addView(authRow)
        configCard.addView(space(8))
        val reconnectRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val reconnectText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        reconnectText.addView(label("Auto reconnect", 14f, textPrimary, false))
        reconnectText.addView(label("Retry when the tunnel drops", 11f, textSecondary, false))
        reconnectRow.addView(reconnectText, LinearLayout.LayoutParams(0, WRAP, 1f))
        autoReconnect = Switch(this).apply { isChecked = true; buttonTintList = null }
        reconnectRow.addView(autoReconnect)
        configCard.addView(reconnectRow)
        root.addView(configCard)

        root.addView(space(14))
        val toolsCard = card()
        toolsCard.addView(sectionTitle("CONFIG FILE"))
        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolsRow.addView(actionButton("IMPORT JSON") { importConfig() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolsRow.addView(actionButton("EXPORT JSON") { exportConfig() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        toolsCard.addView(toolsRow)
        root.addView(toolsCard)

        root.addView(space(14))
        val logCard = card()
        logCard.addView(sectionTitle("CONNECTION LOG"))
        logView = label("Ready. Enter a server and connect.", 12f, textSecondary, false).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(11, 20, 34), 8)
        }
        logCard.addView(logView, LinearLayout.LayoutParams(MATCH, dp(92)))
        root.addView(logCard)
        return scroll
    }

    private fun startOrStop() {
        if (tunnelRequested) {
            tunnelRequested = false
            startService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
            setDisconnected("Stopping tunnel...")
            appendLog("Stop requested")
            return
        }
        val config = readConfig() ?: return
        ConfigStore.save(this, config)
        val permission = VpnService.prepare(this)
        if (permission != null) {
            startActivityForResult(permission, VPN_REQUEST)
            return
        }
        startTunnel(config)
    }

    private fun startTunnel(config: TunnelConfig) {
        val intent = Intent(this, TunnelService::class.java).putExtra(TunnelService.EXTRA_CONFIG, config.toJson())
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        tunnelRequested = true
        connectButton.text = "DISCONNECT"
        statusTitle.text = "Connecting..."
        statusDetail.text = "Starting protected tunnel"
        statusDot.background = circle(primary)
        appendLog("Connecting to ${config.host}:${config.port}")
    }

    private fun readConfig(): TunnelConfig? {
        val value = TunnelConfig(
            name = host.text.toString().trim().ifBlank { "Natat connection" },
            protocol = TunnelProtocol.entries[protocol.selectedItemPosition],
            host = host.text.toString().trim(),
            port = port.text.toString().toIntOrNull() ?: 0,
            username = username.text.toString(),
            password = password.text.toString(),
            autoReconnect = autoReconnect.isChecked
        )
        if (value.host.isBlank() || value.port !in 1..65535) {
            statusTitle.text = "Invalid config"
            statusDetail.text = "Enter a valid host and port"
            appendLog("Invalid host or port")
            return null
        }
        return value
    }

    private fun applyConfig(config: TunnelConfig) {
        host.setText(config.host)
        port.setText(config.port.toString())
        username.setText(config.username)
        password.setText(config.password)
        autoReconnect.isChecked = config.autoReconnect
        protocol.setSelection(TunnelProtocol.entries.indexOf(config.protocol).coerceAtLeast(0))
    }

    private fun importConfig() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, IMPORT_REQUEST)
    }

    private fun importConfig(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("File tidak dapat dibaca")
        }.mapCatching { TunnelConfig.fromJson(it) }.onSuccess {
            ConfigStore.save(this, it)
            applyConfig(it)
            appendLog("Imported ${it.name}")
        }.onFailure { appendLog("Import failed: ${it.message}") }
    }

    private fun exportConfig() {
        val config = readConfig() ?: return
        pendingExport = config.toJson()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "natat-${config.name.replace(" ", "-")}.json")
        }, EXPORT_REQUEST)
    }

    private fun exportConfig(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingExport.orEmpty()) }
                ?: error("File tidak dapat ditulis")
        }.onSuccess { appendLog("Config exported") }
            .onFailure { appendLog("Export failed: ${it.message}") }
        pendingExport = null
    }

    private fun setConnected(detail: String) {
        tunnelRequested = true
        connectButton.text = "DISCONNECT"
        statusTitle.text = "Connected"
        statusDetail.text = detail
        statusDot.background = circle(green)
        appendLog("Tunnel is running")
    }

    private fun setRetrying(detail: String) {
        statusTitle.text = "Reconnecting..."
        statusDetail.text = detail.ifBlank { "Waiting before retry" }
        statusDot.background = circle(primary)
        appendLog("Retry: ${statusDetail.text}")
    }

    private fun setDisconnected(detail: String) {
        connectButton.text = "CONNECT"
        statusTitle.text = "Disconnected"
        statusDetail.text = detail.ifBlank { "No active tunnel" }
        statusDot.background = circle(red)
    }

    private fun appendLog(message: String) {
        if (!::logView.isInitialized) return
        val previous = logView.text.toString().lineSequence().takeLast(3).joinToString("\n")
        logView.text = (if (previous.isBlank()) message else "$previous\n$message").takeLast(400)
    }

    private fun protocolAdapter(): ArrayAdapter<String> = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, TunnelProtocol.entries.map { it.name }
    ) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(textPrimary)
                textSize = 14f
                setPadding(dp(14), 0, dp(14), 0)
            }
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(cardBackground, 14)
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        textSize = 14f
        setTextColor(textPrimary)
        setHintTextColor(textSecondary)
        setPadding(dp(14), 0, dp(14), 0)
        background = rounded(fieldBackground, 10)
    }

    private fun button(text: String, color: Int) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color, 10)
        stateListAnimator = null
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(primary)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(fieldBackground, 9)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun sectionTitle(text: String) = label(text, 11f, primary, true).apply { setPadding(0, 0, 0, dp(13)) }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val VPN_REQUEST = 10
        private const val IMPORT_REQUEST = 30
        private const val EXPORT_REQUEST = 31
        private const val NOTIFICATION_REQUEST = 40
    }
}
