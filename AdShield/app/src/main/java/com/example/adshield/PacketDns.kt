package com.example.adshield

import java.net.InetAddress

/** IPv4 + UDP DNS only. Unsupported packets are not forwarded by this prototype. */
object PacketDns {
    data class Query(val source: ByteArray, val destination: ByteArray, val sourcePort: Int, val destinationPort: Int, val dns: ByteArray)
    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
    private fun put32(b: ByteArray, i: Int, v: Int) { for (n in 0..3) b[i+n] = (v ushr (24-8*n)).toByte() }
    fun parse(packet: ByteArray): Query? {
        if (packet.size < 28 || (packet[0].toInt() ushr 4 and 15) != 4) return null
        val ihl = (packet[0].toInt() and 15) * 4
        if (ihl < 20 || packet.size < ihl + 8 || (packet[9].toInt() and 255) != 17) return null
        if ((u16(packet, 6) and 0x3fff) != 0) return null // no fragmented datagrams
        val total = u16(packet, 2)
        if (total > packet.size || total < ihl + 8) return null
        val udpLength = u16(packet, ihl + 4)
        if (udpLength < 8 || ihl + udpLength > total) return null
        return Query(packet.copyOfRange(12, 16), packet.copyOfRange(16, 20), u16(packet, ihl), u16(packet, ihl+2), packet.copyOfRange(ihl+8, ihl+udpLength))
    }
    fun questionName(dns: ByteArray): String? {
        if (dns.size < 17 || (dns[2].toInt() and 0x80) != 0 || (u16(dns,4) != 1)) return null
        val labels = mutableListOf<String>(); var p = 12; var total = 0
        while (p < dns.size) {
            val len = dns[p].toInt() and 255; p++
            if (len == 0) return if (p + 4 <= dns.size) labels.joinToString(".") else null
            if (len > 63 || p + len > dns.size || ++total > 100) return null
            labels += dns.copyOfRange(p, p + len).toString(Charsets.US_ASCII); p += len
        }
        return null
    }
    fun refused(dns: ByteArray): ByteArray {
        val reply = dns.copyOf()
        if (reply.size >= 12) {
            reply[2] = ((reply[2].toInt() and 0x79) or 0x80).toByte() // response, preserve RD
            reply[3] = ((reply[3].toInt() and 0xF0) or 3).toByte() // NXDOMAIN
            for (i in 6..11) reply[i] = 0 // no answers, authority, additional
        }
        return reply
    }
    private fun checksum(bytes: ByteArray): Int {
        var sum = 0L; var i = 0
        while (i < bytes.size) { sum += ((bytes[i].toInt() and 255) shl 8) + (if (i+1 < bytes.size) bytes[i+1].toInt() and 255 else 0); i += 2 }
        while ((sum ushr 16) != 0L) sum = (sum and 65535) + (sum ushr 16)
        return sum.inv().toInt() and 65535
    }
    fun response(query: Query, dns: ByteArray): ByteArray {
        val out = ByteArray(28 + dns.size)
        out[0] = 0x45; put16(out, 2, out.size); out[8] = 64; out[9] = 17
        query.destination.copyInto(out, 12); query.source.copyInto(out, 16)
        put16(out, 10, checksum(out.copyOfRange(0,20)))
        put16(out, 20, query.destinationPort); put16(out,22,query.sourcePort)
        put16(out,24,dns.size+8); dns.copyInto(out,28)
        val pseudo = ByteArray(12 + 8 + dns.size)
        query.destination.copyInto(pseudo,0); query.source.copyInto(pseudo,4)
        pseudo[9] = 17; put16(pseudo,10,dns.size+8)
        out.copyInto(pseudo,12,20)
        val c = checksum(pseudo); put16(out,26,if (c == 0) 65535 else c)
        return out
    }
}
