package com.natat.tunnel

import android.content.Context
import org.json.JSONObject

enum class TunnelProtocol {
    SSH, HTTP_PROXY, SOCKS5, TLS, WEBSOCKET
}

data class TunnelConfig(
    val name: String = "Default",
    val protocol: TunnelProtocol = TunnelProtocol.SOCKS5,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val sni: String = "",
    val path: String = "/",
    val autoReconnect: Boolean = true,
    val connectTimeoutMs: Int = 10_000,
    val keepAliveSeconds: Int = 25
) {
    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("name", name)
        put("protocol", protocol.name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
        put("sni", sni)
        put("path", path)
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
            return TunnelConfig(
                name = json.optString("name", "Default"),
                protocol = protocol,
                host = json.optString("host", ""),
                port = json.optInt("port", 1080).coerceIn(1, 65535),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                sni = json.optString("sni", ""),
                path = json.optString("path", "/").ifBlank { "/" },
                autoReconnect = json.optBoolean("autoReconnect", true),
                connectTimeoutMs = json.optInt("connectTimeoutMs", 10_000).coerceIn(1_000, 60_000),
                keepAliveSeconds = json.optInt("keepAliveSeconds", 25).coerceIn(5, 120)
            )
        }
    }
}

object ConfigStore {
    private const val PREFS = "tunnel_config"
    private const val JSON = "json"

    fun load(context: Context): TunnelConfig {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(JSON, null)
            ?: return TunnelConfig()
        return runCatching { TunnelConfig.fromJson(raw) }.getOrDefault(TunnelConfig())
    }

    fun save(context: Context, config: TunnelConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(JSON, config.toJson())
            .apply()
    }
}
