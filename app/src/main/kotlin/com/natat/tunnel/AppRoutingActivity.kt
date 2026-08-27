package com.natat.tunnel

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

class AppRoutingActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var list: ListView
    private var apps: List<InstalledApp> = emptyList()
    private lateinit var profile: TunnelConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        profile = ConfigStore.profiles(this).firstOrNull { it.id == profileId } ?: ConfigStore.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        root.addView(TextView(this).apply { text = "Bypass selected apps"; textSize = 22f })
        root.addView(TextView(this).apply { text = "Selected apps use the normal network instead of the VPN."; textSize = 13f })
        list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply { text = "SAVE APP ROUTING"; setOnClickListener { save() } })
        setContentView(root)
        loadApps()
    }

    private fun loadApps() {
        executor.execute {
            apps = packageManager.getInstalledApplications(0)
                .filter { it.packageName != packageName }
                .map { InstalledApp(packageManager.getApplicationLabel(it).toString(), it.packageName) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, apps.map { "${it.label}\n${it.packageName}" })
                apps.forEachIndexed { index, app -> list.setItemChecked(index, app.packageName in profile.bypassPackages) }
            }
        }
    }

    private fun save() {
        val selected = apps.filterIndexed { index, _ -> list.isItemChecked(index) }.map { it.packageName }
        ConfigStore.save(this, profile.copy(bypassPackages = selected))
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class InstalledApp(val label: String, val packageName: String)

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
