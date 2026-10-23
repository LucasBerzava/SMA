package com.example.adshield

import android.content.Context
import java.util.Locale

class FilterStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("filters", Context.MODE_PRIVATE)
    private val bundled: Set<String> by lazy {
        context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        }
    }
    private fun parse(text: String): Set<String> = text.lines().map { it.trim().lowercase(Locale.ROOT).trim('.') }
        .filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
    fun blocked(): Set<String> = bundled + parse(prefs.getString("custom", "") ?: "")
    fun allowed(): Set<String> = parse(prefs.getString("allow", "") ?: "")
    fun customText(): String = prefs.getString("custom", "") ?: ""
    fun allowText(): String = prefs.getString("allow", "") ?: ""
    fun save(custom: String, allow: String) { prefs.edit().putString("custom", custom).putString("allow", allow).apply() }
    private fun matches(domain: String, rule: String) = domain == rule || domain.endsWith(".$rule")
    fun shouldBlock(domain: String): Boolean {
        val normalized = domain.lowercase(Locale.ROOT).trimEnd('.')
        if (allowed().any { matches(normalized, it) }) return false
        return blocked().any { matches(normalized, it) }
    }
}
