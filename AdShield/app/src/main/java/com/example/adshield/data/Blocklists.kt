package com.example.adshield.data

import android.content.Context
import android.util.Log
import com.example.adshield.core.DomainSet
import com.example.adshield.core.ListParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** O sursă de reguli: fie inclusă în APK (asset), fie descărcată de pe internet. */
data class BlocklistSource(
    val id: String,
    val name: String,
    val description: String,
    val url: String? = null,
    val asset: String? = null,
)

object Blocklists {
    private const val TAG = "AdShield.Lists"
    private const val HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard"

    val CATALOG = listOf(
        BlocklistSource("builtin", "StevenBlack (inclusă)",
            "~78.000 domenii de reclame și malware. Funcționează și fără internet.", asset = "stevenblack.txt"),
        BlocklistSource("hagezi_light", "HaGeZi Light",
            "Reclame, trackere, telemetrie. Foarte sigură, aproape fără erori.", url = "$HAGEZI/light-onlydomains.txt"),
        BlocklistSource("hagezi_pro", "HaGeZi Pro (recomandată)",
            "~225.000 domenii: reclame din aplicații/jocuri, trackere, pop-up-uri. Echilibru foarte bun.", url = "$HAGEZI/pro-onlydomains.txt"),
        BlocklistSource("hagezi_proplus", "HaGeZi Pro++ (agresivă)",
            "Blochează mai mult; ocazional strică linkuri sponsorizate sau login-uri.", url = "$HAGEZI/pro.plus-onlydomains.txt"),
        BlocklistSource("oisd_big", "OISD Big",
            "Listă mare, întreținută atent, puține false pozitive.", url = "https://big.oisd.nl/domainswild2"),
        BlocklistSource("adguard_dns", "AdGuard DNS filter",
            "Filtrul folosit de serverele AdGuard DNS (include excepții).", url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"),
        BlocklistSource("onehosts_lite", "1Hosts Lite",
            "Alternativă echilibrată pentru reclame și trackere.", url = "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/domains.wildcards"),
        BlocklistSource("hagezi_tif", "HaGeZi Threat Intelligence",
            "Malware, phishing, înșelătorii (nu reclame).", url = "$HAGEZI/tif.mini-onlydomains.txt"),
        BlocklistSource("hagezi_doh", "HaGeZi DoH bypass",
            "Servere DNS criptate pe care unele aplicații le folosesc ca să ocolească filtrul.", url = "$HAGEZI/doh-onlydomains.txt"),
        BlocklistSource("hagezi_gambling", "HaGeZi Gambling",
            "Site-uri și reclame de pariuri/jocuri de noroc.", url = "$HAGEZI/gambling.mini-onlydomains.txt"),
    )
    val DEFAULT_ENABLED = setOf("builtin", "hagezi_pro")
    private const val MAX_AGE_MS = 3L * 24 * 3600 * 1000

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating

    private fun dir(ctx: Context) = File(ctx.filesDir, "lists").apply { mkdirs() }
    fun file(ctx: Context, id: String) = File(dir(ctx), "$id.txt")
    private fun meta(ctx: Context) = ctx.getSharedPreferences("lists_meta", Context.MODE_PRIVATE)

    fun lastUpdated(ctx: Context, id: String): Long = file(ctx, id).let { if (it.exists()) meta(ctx).getLong("time_$id", it.lastModified()) else 0 }
    fun ruleCount(ctx: Context, id: String): Int = meta(ctx).getInt("count_$id", 0)
    fun lastError(ctx: Context, id: String): String? = meta(ctx).getString("error_$id", null)
    fun isAvailable(ctx: Context, src: BlocklistSource) = src.asset != null || file(ctx, src.id).exists()

    fun needsUpdate(ctx: Context, enabled: Set<String>): Boolean = CATALOG.any {
        it.id in enabled && it.url != null && System.currentTimeMillis() - lastUpdated(ctx, it.id) > MAX_AGE_MS
    }

    /** Citește liniile unei surse (asset sau fișier descărcat); null dacă nu e disponibilă. */
    fun <T> useLines(ctx: Context, src: BlocklistSource, block: (Sequence<String>) -> T): T? {
        val reader = when {
            src.asset != null -> ctx.assets.open(src.asset).bufferedReader()
            file(ctx, src.id).exists() -> file(ctx, src.id).bufferedReader()
            else -> return null
        }
        return reader.useLines(block)
    }

    /**
     * Descarcă listele activate (doar dacă sunt mai vechi decât maxAge, sau toate dacă force).
     * Întoarce un mesaj scurt pentru interfață. Se apelează pe un thread de fundal.
     */
    fun update(ctx: Context, force: Boolean): String {
        if (!_updating.compareAndSet(false, true)) return "Actualizare deja în curs"
        try {
            val enabled = Settings(ctx).enabledLists
            var ok = 0; var failed = 0; var unchanged = 0
            for (src in CATALOG) {
                if (src.id !in enabled || src.url == null) continue
                if (!force && System.currentTimeMillis() - lastUpdated(ctx, src.id) < MAX_AGE_MS) continue
                when (download(ctx, src)) {
                    Result.UPDATED -> ok++
                    Result.NOT_MODIFIED -> unchanged++
                    Result.FAILED -> failed++
                }
            }
            Settings(ctx).lastAutoUpdate = System.currentTimeMillis()
            FilterEngine.reload(ctx)
            return buildString {
                append("Actualizate: $ok")
                if (unchanged > 0) append(", neschimbate: $unchanged")
                if (failed > 0) append(", eșuate: $failed")
                append(" • ${"%,d".format(FilterEngine.ruleCount)} reguli active")
            }
        } finally {
            _updating.value = false
        }
    }

    private enum class Result { UPDATED, NOT_MODIFIED, FAILED }

    private fun download(ctx: Context, src: BlocklistSource): Result {
        val m = meta(ctx)
        val target = file(ctx, src.id)
        val tmp = File(target.parentFile, "${src.id}.tmp")
        var conn: HttpURLConnection? = null
        try {
            var url = URL(src.url)
            var redirects = 0
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "AdShield/1.0 (Android)")
                    if (target.exists()) {
                        m.getString("etag_${src.id}", null)?.let { setRequestProperty("If-None-Match", it) }
                        m.getString("lastmod_${src.id}", null)?.let { setRequestProperty("If-Modified-Since", it) }
                    }
                }
                val code = conn.responseCode
                if (code in 300..399 && code != 304 && redirects++ < 5) {
                    url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
                }
                if (code == 304) {
                    m.edit().putLong("time_${src.id}", System.currentTimeMillis()).remove("error_${src.id}").apply()
                    return Result.NOT_MODIFIED
                }
                if (code != 200) error("HTTP $code")
                break
            }
            val c = conn!!
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            // Validare: lista trebuie să conțină reguli reale înainte să o înlocuim pe cea veche.
            var count = 0
            tmp.bufferedReader().useLines { count = ListParser.parse(it, {}, {}) }
            if (count < 50) error("listă invalidă ($count reguli)")
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            m.edit()
                .putLong("time_${src.id}", System.currentTimeMillis())
                .putInt("count_${src.id}", count)
                .putString("etag_${src.id}", c.getHeaderField("ETag"))
                .putString("lastmod_${src.id}", c.getHeaderField("Last-Modified"))
                .remove("error_${src.id}")
                .apply()
            Log.i(TAG, "${src.id}: $count reguli")
            return Result.UPDATED
        } catch (e: Exception) {
            Log.w(TAG, "${src.id} a eșuat: ${e.message}")
            m.edit().putString("error_${src.id}", e.message ?: e.javaClass.simpleName).apply()
            tmp.delete()
            return Result.FAILED
        } finally {
            conn?.disconnect()
        }
    }

    /** Lista de anti-ocolire inclusă (DoH public + canary Firefox). */
    fun antiBypass(ctx: Context): DomainSet {
        val b = DomainSet.Builder(64)
        ctx.assets.open("anti_bypass.txt").bufferedReader().useLines { ListParser.parse(it, b::add) }
        return b.build()
    }
}
