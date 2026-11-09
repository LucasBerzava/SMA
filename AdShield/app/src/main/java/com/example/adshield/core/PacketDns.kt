package com.example.adshield.core

/**
 * Parser/constructor minimal pentru pachete IPv4/IPv6 + UDP + DNS.
 * Fără dependențe Android, ca să poată fi testat pe JVM.
 */
object PacketDns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val RCODE_OK = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    class Query(
        val ipVersion: Int,
        val source: ByteArray,
        val destination: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val dns: ByteArray,
    )

    /** Întrebarea DNS: numele (litere mici, fără punct final), tipul și offsetul de după ea. */
    class Question(val name: String, val type: Int, val end: Int)

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
    private fun put32(b: ByteArray, i: Int, v: Int) { for (n in 0..3) b[i + n] = (v ushr (24 - 8 * n)).toByte() }

    /** Extrage o cerere UDP dintr-un pachet IP citit din interfața tun. */
    fun parse(packet: ByteArray, length: Int = packet.size): Query? {
        if (length < 1) return null
        return when ((packet[0].toInt() ushr 4) and 15) {
            4 -> parseV4(packet, length)
            6 -> parseV6(packet, length)
            else -> null
        }
    }

    private fun parseV4(p: ByteArray, length: Int): Query? {
        if (length < 28) return null
        val ihl = (p[0].toInt() and 15) * 4
        if (ihl < 20 || length < ihl + 8 || (p[9].toInt() and 255) != 17) return null
        if ((u16(p, 6) and 0x3fff) != 0) return null // fragmente: nu
        val total = u16(p, 2)
        if (total > length || total < ihl + 8) return null
        val udpLength = u16(p, ihl + 4)
        if (udpLength < 8 || ihl + udpLength > total) return null
        return Query(
            4, p.copyOfRange(12, 16), p.copyOfRange(16, 20),
            u16(p, ihl), u16(p, ihl + 2), p.copyOfRange(ihl + 8, ihl + udpLength),
        )
    }

    private fun parseV6(p: ByteArray, length: Int): Query? {
        if (length < 48) return null
        if ((p[6].toInt() and 255) != 17) return null // fără extension headers
        val payload = u16(p, 4)
        if (40 + payload > length || payload < 8) return null
        val udpLength = u16(p, 44)
        if (udpLength < 8 || udpLength > payload) return null
        return Query(
            6, p.copyOfRange(8, 24), p.copyOfRange(24, 40),
            u16(p, 40), u16(p, 42), p.copyOfRange(48, 40 + udpLength),
        )
    }

    /** Citește întrebarea unei cereri DNS standard (QR=0, OPCODE=0, QDCOUNT=1). */
    fun question(dns: ByteArray): Question? {
        if (dns.size < 17) return null
        if ((dns[2].toInt() and 0xF8) != 0) return null // răspuns sau opcode nestandard
        if (u16(dns, 4) != 1) return null
        val sb = StringBuilder()
        var p = 12
        while (true) {
            if (p >= dns.size) return null
            val len = dns[p].toInt() and 255
            p++
            if (len == 0) break
            if (len > 63 || p + len > dns.size || sb.length + len > 253) return null // fără compresie în întrebare
            if (sb.isNotEmpty()) sb.append('.')
            for (i in p until p + len) {
                var c = (dns[i].toInt() and 255).toChar()
                if (c in 'A'..'Z') c += 32
                sb.append(c)
            }
            p += len
        }
        if (p + 4 > dns.size) return null
        return Question(sb.toString(), u16(dns, p), p + 4)
    }

    /** Antet de răspuns + întrebarea copiată; restul secțiunilor goale. */
    private fun header(dns: ByteArray, q: Question, rcode: Int, answers: Int, extra: Int): ByteArray {
        val out = ByteArray(q.end + extra)
        dns.copyInto(out, 0, 0, q.end)
        out[2] = (0x80 or (dns[2].toInt() and 0x01)).toByte() // QR=1, păstrăm RD
        out[3] = (0x80 or rcode).toByte()                      // RA=1
        put16(out, 4, 1); put16(out, 6, answers); put16(out, 8, 0); put16(out, 10, 0)
        return out
    }

    /** Răspuns de eroare (SERVFAIL, NXDOMAIN) fără înregistrări. */
    fun errorResponse(dns: ByteArray, q: Question, rcode: Int): ByteArray = header(dns, q, rcode, 0, 0)

    /**
     * Răspuns pentru un domeniu blocat.
     * nullIp=true: A -> 0.0.0.0, AAAA -> ::, alte tipuri -> NXDOMAIN.
     * nullIp=false: NXDOMAIN pentru orice.
     */
    fun blockedResponse(dns: ByteArray, q: Question, nullIp: Boolean, ttl: Int = 30): ByteArray {
        val addrLen = when {
            !nullIp -> 0
            q.type == TYPE_A -> 4
            q.type == TYPE_AAAA -> 16
            else -> 0
        }
        if (addrLen == 0) return header(dns, q, RCODE_NXDOMAIN, 0, 0)
        val out = header(dns, q, RCODE_OK, 1, 12 + addrLen)
        var i = q.end
        out[i++] = 0xC0.toByte(); out[i++] = 0x0C // pointer la numele din întrebare
        put16(out, i, q.type); i += 2
        put16(out, i, 1); i += 2 // IN
        put32(out, i, ttl); i += 4
        put16(out, i, addrLen) // urmează adresa: zerouri
        return out
    }

    /** Răspuns trunchiat (TC=1) când răspunsul nu încape în MTU. */
    fun truncated(dns: ByteArray, q: Question): ByteArray =
        header(dns, q, RCODE_OK, 0, 0).also { it[2] = (it[2].toInt() or 0x02).toByte() }

    private fun checksum(b: ByteArray, from: Int, to: Int, initial: Long = 0): Int {
        var sum = initial
        var i = from
        while (i < to) {
            sum += ((b[i].toInt() and 255) shl 8) + (if (i + 1 < to) b[i + 1].toInt() and 255 else 0)
            i += 2
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun sumWords(b: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i < b.size) { sum += ((b[i].toInt() and 255) shl 8) + (if (i + 1 < b.size) b[i + 1].toInt() and 255 else 0); i += 2 }
        return sum
    }

    /** Construiește pachetul IP+UDP de răspuns către clientul care a trimis [query]. */
    fun buildPacket(query: Query, dns: ByteArray): ByteArray {
        val udpLen = dns.size + 8
        return if (query.ipVersion == 4) {
            val out = ByteArray(20 + udpLen)
            out[0] = 0x45; put16(out, 2, out.size); out[8] = 64; out[9] = 17
            query.destination.copyInto(out, 12); query.source.copyInto(out, 16)
            put16(out, 10, checksum(out, 0, 20))
            put16(out, 20, query.destinationPort); put16(out, 22, query.sourcePort); put16(out, 24, udpLen)
            dns.copyInto(out, 28)
            val pseudo = sumWords(query.destination) + sumWords(query.source) + 17 + udpLen
            val c = checksum(out, 20, out.size, pseudo)
            put16(out, 26, if (c == 0) 0xFFFF else c)
            out
        } else {
            val out = ByteArray(40 + udpLen)
            out[0] = 0x60; put16(out, 4, udpLen); out[6] = 17; out[7] = 64
            query.destination.copyInto(out, 8); query.source.copyInto(out, 24)
            put16(out, 40, query.destinationPort); put16(out, 42, query.sourcePort); put16(out, 44, udpLen)
            dns.copyInto(out, 48)
            val pseudo = sumWords(query.destination) + sumWords(query.source) + 17 + udpLen
            val c = checksum(out, 40, out.size, pseudo)
            put16(out, 46, if (c == 0) 0xFFFF else c) // obligatoriu în IPv6
            out
        }
    }

    fun typeName(type: Int): String = when (type) {
        1 -> "A"; 28 -> "AAAA"; 5 -> "CNAME"; 65 -> "HTTPS"; 64 -> "SVCB"; 16 -> "TXT"
        12 -> "PTR"; 15 -> "MX"; 33 -> "SRV"; 2 -> "NS"; 6 -> "SOA"
        else -> "T$type"
    }
}
