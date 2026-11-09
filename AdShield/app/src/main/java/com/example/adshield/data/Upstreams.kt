package com.example.adshield.data

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Serverul către care trimitem cererile permise. */
interface Upstream { fun resolve(query: ByteArray): ByteArray }

data class UpstreamOption(val id: String, val name: String, val description: String, val doh: String?, val udp: List<String>)

object Upstreams {
    const val DEFAULT_ID = "adguard"
    const val SYSTEM_ID = "system"

    val OPTIONS = listOf(
        UpstreamOption("adguard", "AdGuard DNS",
            "Blochează și el reclame pe server — un al doilea strat de filtrare.",
            "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
        UpstreamOption("cloudflare", "Cloudflare 1.1.1.1", "Rapid, fără filtrare.",
            "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        UpstreamOption("quad9", "Quad9", "Blochează domenii de malware; fără reclame.",
            "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
        UpstreamOption("google", "Google", "Fără filtrare.",
            "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        UpstreamOption(SYSTEM_ID, "DNS-ul rețelei",
            "Serverul primit de la Wi-Fi/operator (util pentru rețele de firmă sau hotel). Necriptat.", null, emptyList()),
    )

    fun option(id: String) = OPTIONS.firstOrNull { it.id == id } ?: OPTIONS.first()
}

/** DNS clasic prin UDP, încearcă serverele pe rând. */
class UdpUpstream(
    private val servers: () -> List<InetAddress>,
    private val protect: (DatagramSocket) -> Boolean,
    private val timeoutMs: Int = 2500,
) : Upstream {
    override fun resolve(query: ByteArray): ByteArray {
        var last: Exception? = null
        for (server in servers()) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = timeoutMs
                    socket.send(DatagramPacket(query, query.size, server, 53))
                    val buf = ByteArray(8192)
                    while (true) {
                        val reply = DatagramPacket(buf, buf.size)
                        socket.receive(reply)
                        // ID-ul trebuie să corespundă (protecție minimă anti-spoofing)
                        if (reply.length >= 12 && buf[0] == query[0] && buf[1] == query[1]) return buf.copyOf(reply.length)
                    }
                }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("niciun server DNS")
    }
}

/** DNS-over-HTTPS (RFC 8484), POST application/dns-message. Conexiunile HTTP sunt refolosite (keep-alive). */
class DohUpstream(private val url: String, private val timeoutMs: Int = 4000) : Upstream {
    override fun resolve(query: ByteArray): ByteArray {
        // ID 0 recomandat de RFC 8484 (cache HTTP); îl restaurăm în răspuns.
        val body = query.copyOf().also { it[0] = 0; it[1] = 0 }
        // Nu apelăm disconnect(): ar închide socketul și am pierde keep-alive.
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/dns-message")
        conn.setRequestProperty("Accept", "application/dns-message")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        if (conn.responseCode != 200) { conn.disconnect(); error("DoH HTTP ${conn.responseCode}") }
        val out = ByteArrayOutputStream(512)
        conn.inputStream.use { it.copyTo(out) }
        val resp = out.toByteArray()
        if (resp.size < 12) error("răspuns DoH prea scurt")
        resp[0] = query[0]; resp[1] = query[1]
        return resp
    }
}

/**
 * Încearcă primarul (DoH); la eroare trece pe secundar (UDP).
 * După o eroare, ocolește primarul 60 s ca să nu aștepte timeout la fiecare cerere.
 */
class FallbackUpstream(private val primary: Upstream, private val secondary: Upstream) : Upstream {
    @Volatile private var primaryDownUntil = 0L

    override fun resolve(query: ByteArray): ByteArray {
        if (System.currentTimeMillis() >= primaryDownUntil) {
            try { return primary.resolve(query) } catch (_: Exception) {
                primaryDownUntil = System.currentTimeMillis() + 60_000
            }
        }
        return secondary.resolve(query)
    }
}
