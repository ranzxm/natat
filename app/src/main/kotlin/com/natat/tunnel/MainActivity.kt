package com.natat.tunnel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder

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
    private lateinit var profileName: EditText
    private lateinit var profilePicker: Spinner
    private lateinit var port: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var setupMode: Spinner
    private lateinit var sshHost: EditText
    private lateinit var sshPort: EditText
    private lateinit var sshUsername: EditText
    private lateinit var sshPassword: EditText
    private lateinit var httpPayload: EditText
    private lateinit var sni: EditText
    private lateinit var sshSection: LinearLayout
    private lateinit var proxySection: LinearLayout
    private lateinit var proxyAuthSection: LinearLayout
    private lateinit var payloadSection: LinearLayout
    private lateinit var tlsSection: LinearLayout
    private lateinit var autoReconnect: Switch
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var connectButton: Button
    private lateinit var logView: TextView
    private lateinit var trafficView: TextView
    private var tunnelRequested = false
    private var pendingExport: String? = null
    private var activeConfig = TunnelConfig()
    private var updatingProfiles = false
    private var updatingMode = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(TunnelService.EXTRA_STATE).orEmpty()
            val detail = intent?.getStringExtra(TunnelService.EXTRA_DETAIL).orEmpty()
            when (state) {
                TunnelService.STATE_CONNECTED -> setConnected(detail.ifBlank { "SOCKS5 relay active" })
                TunnelService.STATE_ERROR -> setRetrying(detail)
                TunnelService.STATE_STATS -> updateTraffic(
                    intent?.getLongExtra(TunnelService.EXTRA_TX_BYTES, 0) ?: 0,
                    intent?.getLongExtra(TunnelService.EXTRA_RX_BYTES, 0) ?: 0
                )
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
        if (::logView.isInitialized) showStoredLog()
    }

    override fun onPause() {
        unregisterReceiver(stateReceiver)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.let { result ->
            result.contents?.let { importConfig(it) } ?: appendLog("QR scan cancelled")
            return
        }
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startTunnel(ConfigStore.load(this))
            return
        }
        if (requestCode == APP_ROUTING_REQUEST) {
            if (resultCode == RESULT_OK) applyConfig(ConfigStore.load(this))
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            IMPORT_REQUEST -> importConfig(data.data!!)
            EXPORT_REQUEST -> exportConfig(data.data!!)
        }
    }

    private fun buildView(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(this@MainActivity.background); isFillViewport = true }
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

        val profileCard = card()
        profileCard.addView(sectionTitle("ACTIVE PROFILE"))
        val profileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        profilePicker = Spinner(this).apply { background = rounded(fieldBackground, 10) }
        profileRow.addView(profilePicker, LinearLayout.LayoutParams(0, dp(48), 1f))
        profileRow.addView(actionButton("NEW") { createProfile() }, LinearLayout.LayoutParams(dp(68), dp(42)).apply { marginStart = dp(8) })
        profileRow.addView(actionButton("DELETE") { deleteProfile() }, LinearLayout.LayoutParams(dp(76), dp(42)).apply { marginStart = dp(6) })
        profileCard.addView(profileRow)
        root.addView(profileCard)
        root.addView(space(14))

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
        trafficView = label("DOWN 0 B   UP 0 B", 11f, textSecondary, true).apply { setPadding(0, dp(10), 0, 0) }
        statusCard.addView(trafficView)
        statusCard.addView(space(16))
        connectButton = button("CONNECT", primary).apply { setOnClickListener { startOrStop() } }
        statusCard.addView(connectButton, LinearLayout.LayoutParams(MATCH, dp(48)))
        root.addView(statusCard)

        root.addView(space(14))
        val configCard = card()
        configCard.addView(sectionTitle("TUNNEL SETUP"))
        profileName = field("Profile name")
        configCard.addView(profileName, LinearLayout.LayoutParams(MATCH, dp(52)))
        configCard.addView(space(12))
        configCard.addView(label("Connection type", 12f, textSecondary, false))
        setupMode = Spinner(this).apply {
            background = rounded(fieldBackground, 10)
            adapter = modeAdapter()
        }
        configCard.addView(setupMode, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(6) })
        configCard.addView(space(12))

        proxySection = setupSection("1. REMOTE PROXY")
        proxySection.addView(label("SOCKS5 upstream or the server that receives your HTTP payload", 11f, textSecondary, false))
        val proxyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        host = field("Proxy host")
        port = field("Proxy port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        proxyRow.addView(host, LinearLayout.LayoutParams(0, dp(52), 2f))
        proxyRow.addView(port, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        proxySection.addView(proxyRow)
        proxyAuthSection = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        username = field("SOCKS username (optional)")
        password = field("SOCKS password (optional)").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        proxyAuthSection.addView(username, LinearLayout.LayoutParams(0, dp(52), 1f))
        proxyAuthSection.addView(password, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        proxySection.addView(proxyAuthSection)
        configCard.addView(proxySection)

        sshSection = setupSection("2. SSH SERVER")
        sshSection.addView(label("Your SSH account server", 11f, textSecondary, false))
        val sshServerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sshHost = field("SSH host")
        sshPort = field("SSH port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        sshServerRow.addView(sshHost, LinearLayout.LayoutParams(0, dp(52), 2f))
        sshServerRow.addView(sshPort, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        sshSection.addView(sshServerRow)
        val sshAuth = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sshUsername = field("SSH username")
        sshPassword = field("SSH password").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        sshAuth.addView(sshUsername, LinearLayout.LayoutParams(0, dp(52), 1f))
        sshAuth.addView(sshPassword, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        sshSection.addView(sshAuth)
        configCard.addView(sshSection)

        payloadSection = setupSection("3. CUSTOM HTTP PAYLOAD")
        payloadSection.addView(label("Use [host], [port], and [crlf]. Add Proxy-Authorization here if required.", 11f, textSecondary, false))
        httpPayload = field("CONNECT [host]:[port] HTTP/1.1[crlf]...").apply {
            setSingleLine(false)
            minLines = 4
            gravity = Gravity.TOP
        }
        payloadSection.addView(httpPayload, LinearLayout.LayoutParams(MATCH, dp(112)))
        configCard.addView(payloadSection)

        tlsSection = setupSection("4. TLS / SNI")
        tlsSection.addView(label("Certificate verification is always enabled", 11f, textSecondary, false))
        sni = field("SNI server name (optional)")
        tlsSection.addView(sni, LinearLayout.LayoutParams(MATCH, dp(52)))
        configCard.addView(tlsSection)

        configCard.addView(space(8))
        val reconnectRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val reconnectText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        reconnectText.addView(label("Auto reconnect", 14f, textPrimary, false))
        reconnectText.addView(label("Retry when the tunnel drops", 11f, textSecondary, false))
        reconnectRow.addView(reconnectText, LinearLayout.LayoutParams(0, WRAP, 1f))
        autoReconnect = Switch(this).apply { isChecked = true; buttonTintList = null }
        reconnectRow.addView(autoReconnect)
        configCard.addView(reconnectRow)
        configCard.addView(space(10))
        configCard.addView(actionButton("APP ROUTING: BYPASS SELECTED APPS") { openAppRouting() }, LinearLayout.LayoutParams(MATCH, dp(42)))
        configCard.addView(space(8))
        configCard.addView(actionButton("ADVANCED: SSH KEY / FINGERPRINT / DNS") { openAdvancedEditor() }, LinearLayout.LayoutParams(MATCH, dp(42)))
        root.addView(configCard)

        root.addView(space(14))
        val toolsCard = card()
        toolsCard.addView(sectionTitle("CONFIG FILE"))
        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolsRow.addView(actionButton("IMPORT JSON") { importConfig() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolsRow.addView(actionButton("EXPORT JSON") { exportConfig() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        toolsCard.addView(toolsRow)
        toolsCard.addView(space(8))
        val qrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qrRow.addView(actionButton("IMPORT QR") { importQr() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        qrRow.addView(actionButton("SHOW QR") { showQr() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        toolsCard.addView(qrRow)
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
        profilePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (updatingProfiles) return
                val selected = ConfigStore.profiles(this@MainActivity).getOrNull(position) ?: return
                if (selected.id != activeConfig.id) {
                    ConfigStore.save(this@MainActivity, draftConfig(), makeActive = false)
                    ConfigStore.setActive(this@MainActivity, selected.id)
                    applyConfig(selected)
                    appendLog("Selected profile ${selected.name}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        setupMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!updatingMode) updateSetupVisibility()
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
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
        val destination = if (config.protocol == TunnelProtocol.SSH) "${config.sshHost}:${config.sshPort}" else "${config.host}:${config.port}"
        appendLog("Connecting to $destination")
    }

    private fun readConfig(): TunnelConfig? {
        val value = draftConfig()
        val valid = when (value.protocol) {
            TunnelProtocol.SSH -> value.sshHost.isNotBlank() && value.sshPort in 1..65535 &&
                (!value.useHttpPayload || value.host.isNotBlank() && value.port in 1..65535)
            else -> value.host.isNotBlank() && value.port in 1..65535
        }
        if (!valid) {
            statusTitle.text = "Invalid config"
            statusDetail.text = "Enter valid server settings"
            appendLog("Invalid server settings")
            return null
        }
        return value
    }

    private fun draftConfig(): TunnelConfig {
        val mode = SetupMode.entries[setupMode.selectedItemPosition]
        return activeConfig.copy(
            name = profileName.text.toString().trim().ifBlank { "Natat connection" },
            protocol = if (mode == SetupMode.SOCKS5) TunnelProtocol.SOCKS5 else TunnelProtocol.SSH,
            host = host.text.toString().trim(),
            port = port.text.toString().toIntOrNull() ?: 0,
            username = username.text.toString(),
            password = password.text.toString(),
            sshHost = sshHost.text.toString().trim(),
            sshPort = sshPort.text.toString().toIntOrNull() ?: 22,
            sshUsername = sshUsername.text.toString(),
            sshPassword = sshPassword.text.toString(),
            useHttpPayload = mode.usesPayload,
            httpPayload = httpPayload.text.toString(),
            useTls = mode.usesTls,
            sni = sni.text.toString().trim(),
            useWebSocket = false,
            autoReconnect = autoReconnect.isChecked
        )
    }

    private fun applyConfig(config: TunnelConfig) {
        activeConfig = config
        profileName.setText(config.name)
        host.setText(config.host)
        port.setText(config.port.toString())
        username.setText(config.username)
        password.setText(config.password)
        sshHost.setText(config.sshHost)
        sshPort.setText(config.sshPort.toString())
        sshUsername.setText(config.sshUsername)
        sshPassword.setText(config.sshPassword)
        httpPayload.setText(config.httpPayload)
        sni.setText(config.sni)
        autoReconnect.isChecked = config.autoReconnect
        updatingMode = true
        setupMode.setSelection(SetupMode.from(config).ordinal)
        updatingMode = false
        updateSetupVisibility()
        refreshProfilePicker(config.id)
    }

    private fun refreshProfilePicker(selectedId: String) {
        if (!::profilePicker.isInitialized) return
        val profiles = ConfigStore.profiles(this)
        updatingProfiles = true
        profilePicker.adapter = profileAdapter(profiles.map { it.name })
        profilePicker.setSelection(profiles.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
        updatingProfiles = false
    }

    private fun createProfile() {
        val config = TunnelConfig(name = "New profile")
        ConfigStore.save(this, config)
        applyConfig(config)
        appendLog("Created profile")
    }

    private fun deleteProfile() {
        if (ConfigStore.profiles(this).size == 1) {
            appendLog("At least one profile is required")
            return
        }
        ConfigStore.delete(this, activeConfig.id)
        applyConfig(ConfigStore.load(this))
        appendLog("Deleted profile")
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
        }.onSuccess { importConfig(it) }
            .onFailure { appendLog("Import failed: ${it.message}") }
    }

    private fun importConfig(raw: String) {
        runCatching { TunnelConfig.fromJson(raw) }.onSuccess {
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

    private fun importQr() {
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("Scan Natat configuration QR")
            setBeepEnabled(false)
            initiateScan()
        }
    }

    private fun showQr() {
        val config = readConfig() ?: return
        runCatching {
            BarcodeEncoder().encodeBitmap(config.toJson(), BarcodeFormat.QR_CODE, dp(300), dp(300))
        }.onSuccess { bitmap ->
            AlertDialog.Builder(this)
                .setTitle("Natat config QR")
                .setView(ImageView(this).apply { setImageBitmap(bitmap); setPadding(dp(12), dp(12), dp(12), dp(12)) })
                .setPositiveButton("Close", null)
                .show()
        }.onFailure { appendLog("QR export failed: ${it.message}") }
    }

    private fun openAppRouting() {
        ConfigStore.save(this, draftConfig())
        startActivityForResult(Intent(this, AppRoutingActivity::class.java)
            .putExtra(AppRoutingActivity.EXTRA_PROFILE_ID, activeConfig.id), APP_ROUTING_REQUEST)
    }

    private fun setConnected(detail: String) {
        tunnelRequested = true
        connectButton.text = "DISCONNECT"
        statusTitle.text = "Connected"
        statusDetail.text = detail
        statusDot.background = circle(green)
        appendLog("Tunnel is running")
    }

    private fun updateTraffic(txBytes: Long, rxBytes: Long) {
        trafficView.text = "DOWN ${formatBytes(rxBytes)}   UP ${formatBytes(txBytes)}"
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
        val previous = logView.text.toString().lineSequence().toList().takeLast(3).joinToString("\n")
        logView.text = (if (previous.isBlank()) message else "$previous\n$message").takeLast(400)
    }

    private fun showStoredLog() {
        ConnectionLogStore.recent(this, activeConfig.id).takeIf { it.isNotEmpty() }?.let {
            logView.text = it.joinToString("\n")
        }
    }

    private fun openAdvancedEditor() {
        val draft = draftConfig()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        fun heading(value: String) {
            content.addView(sectionTitle(value).apply { setPadding(0, dp(14), 0, dp(8)) })
        }
        fun input(value: String, hint: String, secret: Boolean = false, multiLine: Boolean = false): EditText {
            return field(hint).apply {
                setText(value)
                if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if (multiLine) {
                    setSingleLine(false)
                    minLines = 3
                    gravity = Gravity.TOP
                }
                content.addView(this, LinearLayout.LayoutParams(MATCH, if (multiLine) dp(86) else dp(52)).apply { bottomMargin = dp(8) })
            }
        }
        fun toggle(label: String, checked: Boolean): CheckBox {
            return CheckBox(this).apply {
                text = label
                isChecked = checked
                textSize = 13f
                setTextColor(textPrimary)
                content.addView(this)
            }
        }

        heading("SSH SECURITY")
        val privateKey = input(draft.privateKey, "Private key (paste PEM)", multiLine = true)
        val privateKeyPassphrase = input(draft.privateKeyPassphrase, "Private key passphrase", secret = true)
        val hostKeyFingerprint = input(draft.sshHostKeyFingerprint, "SSH host key fingerprint (optional on first connection)")
        heading("VPN / DNS")
        val dns = input(draft.dnsServers.joinToString(", "), "DNS servers, comma separated")
        val udpEnabled = toggle("Enable UDP relay", draft.udpEnabled)
        val startOnBoot = toggle("Reconnect automatically after device boot", draft.startOnBoot)

        AlertDialog.Builder(this)
            .setTitle("Advanced connection settings")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                activeConfig = draft.copy(
                    privateKey = privateKey.text.toString(),
                    privateKeyPassphrase = privateKeyPassphrase.text.toString(),
                    sshHostKeyFingerprint = hostKeyFingerprint.text.toString().trim(),
                    dnsServers = dns.text.toString().split(',').map { it.trim() }.filter { it.isNotBlank() }
                        .ifEmpty { listOf("1.1.1.1") },
                    udpEnabled = udpEnabled.isChecked,
                    startOnBoot = startOnBoot.isChecked
                )
                ConfigStore.save(this, draftConfig())
                appendLog("Advanced settings saved")
            }
            .show()
    }

    private fun modeAdapter(): ArrayAdapter<String> = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, SetupMode.entries.map { it.label }
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

    private fun updateSetupVisibility() {
        if (!::setupMode.isInitialized) return
        val mode = SetupMode.entries[setupMode.selectedItemPosition]
        proxySection.visibility = if (mode.usesPayload || mode == SetupMode.SOCKS5) View.VISIBLE else View.GONE
        proxyAuthSection.visibility = if (mode == SetupMode.SOCKS5) View.VISIBLE else View.GONE
        sshSection.visibility = if (mode == SetupMode.SOCKS5) View.GONE else View.VISIBLE
        payloadSection.visibility = if (mode.usesPayload) View.VISIBLE else View.GONE
        tlsSection.visibility = if (mode.usesTls) View.VISIBLE else View.GONE
    }

    private fun profileAdapter(items: List<String>): ArrayAdapter<String> = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, items
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

    private fun setupSection(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
        addView(label(title, 11f, primary, true).apply { setPadding(0, 0, 0, dp(6)) })
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

    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KB"
        value < 1024L * 1024 * 1024 -> "${value / (1024 * 1024)} MB"
        else -> "${value / (1024L * 1024 * 1024)} GB"
    }

    private enum class SetupMode(val label: String, val usesPayload: Boolean, val usesTls: Boolean) {
        SOCKS5("SOCKS5 Proxy", false, false),
        SSH_DIRECT("SSH Direct", false, false),
        SSH_HTTP("SSH + Custom HTTP Payload", true, false),
        SSH_TLS("SSH + TLS / SNI", false, true),
        SSH_HTTP_TLS("SSH + HTTP Payload + TLS / SNI", true, true);

        companion object {
            fun from(config: TunnelConfig): SetupMode = when {
                config.protocol == TunnelProtocol.SOCKS5 -> SOCKS5
                config.useHttpPayload && config.useTls -> SSH_HTTP_TLS
                config.useHttpPayload -> SSH_HTTP
                config.useTls -> SSH_TLS
                else -> SSH_DIRECT
            }
        }
    }

    companion object {
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val VPN_REQUEST = 10
        private const val IMPORT_REQUEST = 30
        private const val EXPORT_REQUEST = 31
        private const val NOTIFICATION_REQUEST = 40
        private const val APP_ROUTING_REQUEST = 50
    }
}
