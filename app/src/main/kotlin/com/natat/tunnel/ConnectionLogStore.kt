package com.natat.tunnel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConnectionLogStore {
    private const val PREFS = "connection_log"
    private const val ENTRIES = "entries"
    private const val MAX_ENTRIES = 80

    fun append(context: Context, profileId: String, message: String) {
        val values = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRIES, "[]"))
        values.put(JSONObject().put("profile", profileId).put("time", System.currentTimeMillis()).put("message", message))
        while (values.length() > MAX_ENTRIES) values.remove(0)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ENTRIES, values.toString()).apply()
    }

    fun recent(context: Context, profileId: String): List<String> {
        val values = runCatching {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRIES, "[]"))
        }.getOrDefault(JSONArray())
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
        return (0 until values.length()).mapNotNull { index ->
            values.optJSONObject(index)?.takeIf { it.optString("profile") == profileId }?.let {
                formatter.format(Date(it.optLong("time"))).plus("  ").plus(it.optString("message"))
            }
        }.takeLast(4)
    }
}
