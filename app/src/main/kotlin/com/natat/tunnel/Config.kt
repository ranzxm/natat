package com.natat.tunnel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TunnelProtocol {
    SOCKS5, SSH, HTTP_PROXY, TLS, WEBSOCKET
}

data class TunnelConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New connection",
    val protocol: TunnelProtocol = TunnelProtocol.SOCKS5,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUsername: String = "",
    val sshPassword: String = "",
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
    val sshHostKeyFingerprint: String = "",
    val useHttpPayload: Boolean = false,
    val httpPayload: String = "",
    val useTls: Boolean = false,
    val sni: String = "",
    val skipCertificateVerification: Boolean = false,
    val useWebSocket: Boolean = false,
    val websocketPath: String = "/",
    val websocketHeaders: String = "",
    val dnsServers: List<String> = listOf("1.1.1.1", "2606:4700:4700::1111"),
    val udpEnabled: Boolean = true,
    val autoReconnect: Boolean = true,
    val connectTimeoutMs: Int = 10_000,
    val keepAliveSeconds: Int = 25
) {
    fun toJson(): String = JSONObject().apply {
        put("version", 2)
        put("id", id)
        put("name", name)
        put("protocol", protocol.name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
        put("sshHost", sshHost)
        put("sshPort", sshPort)
        put("sshUsername", sshUsername)
        put("sshPassword", sshPassword)
        put("privateKey", privateKey)
        put("privateKeyPassphrase", privateKeyPassphrase)
        put("sshHostKeyFingerprint", sshHostKeyFingerprint)
        put("useHttpPayload", useHttpPayload)
        put("httpPayload", httpPayload)
        put("useTls", useTls)
        put("sni", sni)
        put("skipCertificateVerification", skipCertificateVerification)
        put("useWebSocket", useWebSocket)
        put("websocketPath", websocketPath)
        put("websocketHeaders", websocketHeaders)
        put("dnsServers", JSONArray(dnsServers))
        put("udpEnabled", udpEnabled)
        put("autoReconnect", autoReconnect)
        put("connectTimeoutMs", connectTimeoutMs)
        put("keepAliveSeconds", keepAliveSeconds)
    }.toString(2)

    companion object {
        fun fromJson(raw: String): TunnelConfig {
            val json = JSONObject(raw)
            val protocol = runCatching {
                TunnelProtocol.valueOf(json.optString("protocol", "SOCKS5").uppercase())
            }.getOrDefault(TunnelProtocol.SOCKS5)
            val dns = json.optJSONArray("dnsServers")?.let { values ->
                List(values.length()) { index -> values.optString(index).trim() }.filter { it.isNotBlank() }
            }.orEmpty().ifEmpty { listOf("1.1.1.1", "2606:4700:4700::1111") }
            return TunnelConfig(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = json.optString("name", "New connection"),
                protocol = protocol,
                host = json.optString("host", ""),
                port = json.optInt("port", 1080).coerceIn(1, 65535),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                sshHost = json.optString("sshHost", ""),
                sshPort = json.optInt("sshPort", 22).coerceIn(1, 65535),
                sshUsername = json.optString("sshUsername", ""),
                sshPassword = json.optString("sshPassword", ""),
                privateKey = json.optString("privateKey", ""),
                privateKeyPassphrase = json.optString("privateKeyPassphrase", ""),
                sshHostKeyFingerprint = json.optString("sshHostKeyFingerprint", ""),
                useHttpPayload = json.optBoolean("useHttpPayload", false),
                httpPayload = json.optString("httpPayload", ""),
                useTls = json.optBoolean("useTls", false),
                sni = json.optString("sni", ""),
                skipCertificateVerification = json.optBoolean("skipCertificateVerification", false),
                useWebSocket = json.optBoolean("useWebSocket", false),
                websocketPath = json.optString("websocketPath", "/").ifBlank { "/" },
                websocketHeaders = json.optString("websocketHeaders", ""),
                dnsServers = dns,
                udpEnabled = json.optBoolean("udpEnabled", true),
                autoReconnect = json.optBoolean("autoReconnect", true),
                connectTimeoutMs = json.optInt("connectTimeoutMs", 10_000).coerceIn(1_000, 60_000),
                keepAliveSeconds = json.optInt("keepAliveSeconds", 25).coerceIn(5, 120)
            )
        }
    }
}

object ConfigStore {
    private const val PREFS = "tunnel_config"
    private const val PROFILES = "profiles"
    private const val ACTIVE_ID = "active_id"
    private const val LEGACY_JSON = "json"

    fun load(context: Context): TunnelConfig {
        val profiles = profiles(context)
        val activeId = preferences(context).getString(ACTIVE_ID, null)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    fun profiles(context: Context): List<TunnelConfig> {
        val prefs = preferences(context)
        val raw = prefs.getString(PROFILES, null) ?: return migrateLegacy(context)
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { TunnelConfig.fromJson(array.getJSONObject(it).toString()) }
        }.getOrDefault(emptyList()).ifEmpty { listOf(TunnelConfig()) }
    }

    fun save(context: Context, config: TunnelConfig, makeActive: Boolean = true) {
        val updated = profiles(context).filterNot { it.id == config.id } + config
        persist(context, updated, if (makeActive) config.id else load(context).id)
    }

    fun delete(context: Context, id: String) {
        val remaining = profiles(context).filterNot { it.id == id }.ifEmpty { listOf(TunnelConfig()) }
        persist(context, remaining, remaining.first().id)
    }

    fun setActive(context: Context, id: String) {
        preferences(context).edit().putString(ACTIVE_ID, id).apply()
    }

    private fun migrateLegacy(context: Context): List<TunnelConfig> {
        val prefs = preferences(context)
        val config = prefs.getString(LEGACY_JSON, null)?.let {
            runCatching { TunnelConfig.fromJson(it) }.getOrNull()
        } ?: TunnelConfig()
        persist(context, listOf(config), config.id)
        return listOf(config)
    }

    private fun persist(context: Context, configs: List<TunnelConfig>, activeId: String) {
        val array = JSONArray()
        configs.forEach { array.put(JSONObject(it.toJson())) }
        preferences(context).edit()
            .putString(PROFILES, array.toString())
            .putString(ACTIVE_ID, activeId)
            .remove(LEGACY_JSON)
            .apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
