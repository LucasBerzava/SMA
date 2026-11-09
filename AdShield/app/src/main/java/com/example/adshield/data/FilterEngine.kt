package com.example.adshield.data

import android.content.Context
import android.util.Log
import com.example.adshield.core.DomainSet
import com.example.adshield.core.ListParser

/**
 * Decide pentru fiecare domeniu dacă e blocat.
 * Ordinea priorităților: excepțiile tale > blocările tale > excepțiile listelor (@@) > listele.
 * Seturile sunt imutabile și înlocuite atomic la reîncărcare, deci căutarea nu are nevoie de lock.
 */
object FilterEngine {
    enum class Decision { BLOCKED, ALLOWED, USER_BLOCKED, USER_ALLOWED }

    private class Rules(
        val userAllow: DomainSet,
        val userBlock: DomainSet,
        val listAllow: DomainSet,
        val listBlock: DomainSet,
        val antiBypass: DomainSet,
    )

    @Volatile private var rules: Rules? = null
    @Volatile var ruleCount: Int = 0
        private set
    private val lock = Any()

    fun ensureLoaded(ctx: Context) { if (rules == null) synchronized(lock) { if (rules == null) reloadLocked(ctx) } }

    fun reload(ctx: Context) = synchronized(lock) { reloadLocked(ctx) }

    private fun reloadLocked(ctx: Context) {
        val t0 = System.currentTimeMillis()
        val settings = Settings(ctx)
        val enabled = settings.enabledLists
        val block = DomainSet.Builder(300_000)
        val allow = DomainSet.Builder(256)
        for (src in Blocklists.CATALOG) {
            if (src.id !in enabled) continue
            try {
                Blocklists.useLines(ctx, src) { ListParser.parse(it, block::add, allow::add) }
            } catch (e: Exception) {
                Log.w("AdShield", "Nu pot citi ${src.id}: ${e.message}")
            }
        }
        val anti = if (settings.antiBypass) Blocklists.antiBypass(ctx) else DomainSet.EMPTY
        val userBlock = DomainSet.Builder(64)
        ListParser.parse(settings.customBlock.lineSequence(), userBlock::add)
        val userAllow = DomainSet.Builder(64)
        ListParser.parse(settings.customAllow.lineSequence(), userAllow::add)
        val listBlock = block.build()
        rules = Rules(userAllow.build(), userBlock.build(), allow.build(), listBlock, anti)
        ruleCount = listBlock.size + anti.size
        Log.i("AdShield", "Reguli încărcate: $ruleCount în ${System.currentTimeMillis() - t0} ms")
    }

    fun decide(domain: String): Decision {
        val r = rules ?: return Decision.ALLOWED
        if (domain.isEmpty()) return Decision.ALLOWED
        return when {
            r.userAllow.matches(domain) -> Decision.USER_ALLOWED
            r.userBlock.matches(domain) -> Decision.USER_BLOCKED
            r.antiBypass.matches(domain) -> Decision.BLOCKED
            r.listAllow.matches(domain) -> Decision.ALLOWED
            r.listBlock.matches(domain) -> Decision.BLOCKED
            else -> Decision.ALLOWED
        }
    }
}
