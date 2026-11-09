package com.example.adshield.data

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/** Contori + jurnalul ultimelor cereri, ținute în memorie și salvate periodic. */
object Stats {
    data class Entry(val time: Long, val domain: String, val type: Int, val blocked: Boolean, val app: String?)

    private const val LOG_SIZE = 1000
    private const val TOP_LIMIT = 5000

    val totalAllTime = AtomicLong()
    val blockedAllTime = AtomicLong()
    val totalSession = AtomicLong()
    val blockedSession = AtomicLong()

    private val log = ArrayDeque<Entry>(LOG_SIZE)
    private val topDomains = HashMap<String, Int>()
    private val topApps = HashMap<String, Int>()
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
        totalAllTime.set(p.getLong("total", 0))
        blockedAllTime.set(p.getLong("blocked", 0))
        loaded = true
    }

    fun flush(ctx: Context) {
        if (!loaded) return
        ctx.getSharedPreferences("stats", Context.MODE_PRIVATE).edit()
            .putLong("total", totalAllTime.get())
            .putLong("blocked", blockedAllTime.get())
            .apply()
    }

    fun startSession() { totalSession.set(0); blockedSession.set(0) }

    fun record(e: Entry) {
        totalAllTime.incrementAndGet(); totalSession.incrementAndGet()
        if (e.blocked) { blockedAllTime.incrementAndGet(); blockedSession.incrementAndGet() }
        synchronized(log) {
            if (log.size >= LOG_SIZE) log.removeFirst()
            log.addLast(e)
            if (e.blocked) {
                if (topDomains.size < TOP_LIMIT || e.domain in topDomains) topDomains.merge(e.domain, 1, Int::plus)
                e.app?.let { if (topApps.size < TOP_LIMIT || it in topApps) topApps.merge(it, 1, Int::plus) }
            }
        }
    }

    /** Cele mai noi cereri primele. */
    fun recent(): List<Entry> = synchronized(log) { log.reversed() }

    fun topBlockedDomains(n: Int) = synchronized(log) { topDomains.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value } }
    fun topBlockedApps(n: Int) = synchronized(log) { topApps.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value } }

    fun reset(ctx: Context) {
        totalAllTime.set(0); blockedAllTime.set(0); totalSession.set(0); blockedSession.set(0)
        synchronized(log) { log.clear(); topDomains.clear(); topApps.clear() }
        flush(ctx)
    }

    fun clearLog() = synchronized(log) { log.clear() }
}
