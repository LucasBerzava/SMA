package com.example.adshield.data

import android.content.Context
import android.content.SharedPreferences

/** Toate setările aplicației, persistate în SharedPreferences. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init { migrateFromPrototype(context) }

    var upstream: String
        get() = p.getString("upstream", Upstreams.DEFAULT_ID) ?: Upstreams.DEFAULT_ID
        set(v) = p.edit().putString("upstream", v).apply()

    /** DNS criptat (DoH) către serverul ales; altfel UDP clasic. */
    var useDoh: Boolean
        get() = p.getBoolean("doh", true)
        set(v) = p.edit().putBoolean("doh", v).apply()

    /** true: răspunde 0.0.0.0 / :: ; false: NXDOMAIN. */
    var nullIpResponse: Boolean
        get() = p.getBoolean("null_ip", true)
        set(v) = p.edit().putBoolean("null_ip", v).apply()

    var autostart: Boolean
        get() = p.getBoolean("autostart", true)
        set(v) = p.edit().putBoolean("autostart", v).apply()

    /** Blochează ocolirea filtrului prin DNS-over-HTTPS al aplicațiilor (Firefox, Chrome etc.). */
    var antiBypass: Boolean
        get() = p.getBoolean("anti_bypass", true)
        set(v) = p.edit().putBoolean("anti_bypass", v).apply()

    var enabledLists: Set<String>
        get() = p.getStringSet("lists", null)?.toSet() ?: Blocklists.DEFAULT_ENABLED
        set(v) = p.edit().putStringSet("lists", v).apply()

    var customBlock: String
        get() = p.getString("custom_block", "") ?: ""
        set(v) = p.edit().putString("custom_block", v).apply()

    var customAllow: String
        get() = p.getString("custom_allow", "") ?: ""
        set(v) = p.edit().putString("custom_allow", v).apply()

    var excludedApps: Set<String>
        get() = p.getStringSet("excluded_apps", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("excluded_apps", v).apply()

    /** Ultima stare dorită de utilizator (pentru repornire la boot). */
    var wantRunning: Boolean
        get() = p.getBoolean("want_running", false)
        set(v) = p.edit().putBoolean("want_running", v).apply()

    var lastAutoUpdate: Long
        get() = p.getLong("last_auto_update", 0)
        set(v) = p.edit().putLong("last_auto_update", v).apply()

    fun addRule(domain: String, allow: Boolean) {
        val (add, remove) = if (allow) customAllow to customBlock else customBlock to customAllow
        val addLines = add.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (domain !in addLines) addLines += domain
        val removeLines = remove.lines().map { it.trim() }.filter { it.isNotEmpty() && it != domain }
        if (allow) { customAllow = addLines.joinToString("\n"); customBlock = removeLines.joinToString("\n") }
        else { customBlock = addLines.joinToString("\n"); customAllow = removeLines.joinToString("\n") }
    }

    private fun migrateFromPrototype(context: Context) {
        if (p.getBoolean("migrated", false)) return
        val old = context.getSharedPreferences("filters", Context.MODE_PRIVATE)
        p.edit()
            .putString("custom_block", old.getString("custom", "") ?: "")
            .putString("custom_allow", old.getString("allow", "") ?: "")
            .putBoolean("migrated", true)
            .apply()
    }
}
