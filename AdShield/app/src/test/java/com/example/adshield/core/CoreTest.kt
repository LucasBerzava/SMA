package com.example.adshield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun dnsQuery(name: String, type: Int, id: Int = 0x1234, edns: Boolean = false): ByteArray {
        val out = ArrayList<Byte>()
        fun b(v: Int) { out.add(v.toByte()) }
        b(id shr 8); b(id); b(0x01); b(0x00); b(0); b(1); b(0); b(0); b(0); b(0); b(0); b(if (edns) 1 else 0)
        for (label in name.split('.')) { b(label.length); label.forEach { b(it.code) } }
        b(0); b(type shr 8); b(type); b(0); b(1)
        if (edns) { b(0); b(0); b(41); b(0x10); b(0); b(0); b(0); b(0); b(0); b(0); b(0) }
        return out.toByteArray()
    }

    private fun ipv4Udp(dns: ByteArray): ByteArray {
        val p = ByteArray(28 + dns.size)
        p[0] = 0x45; p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte(); p[8] = 64; p[9] = 17
        byteArrayOf(10, 111, 0, 2).copyInto(p, 12); byteArrayOf(10, 111, 0, 1).copyInto(p, 16)
        p[20] = 0xAB.toByte(); p[21] = 0xCD.toByte(); p[23] = 53
        p[24] = ((dns.size + 8) shr 8).toByte(); p[25] = (dns.size + 8).toByte()
        dns.copyInto(p, 28)
        return p
    }

    @Test fun parsesQuestion() {
        val q = PacketDns.question(dnsQuery("Ads.Example.COM", 28, edns = true))
        assertNotNull(q); q!!
        assertEquals("ads.example.com", q.name)
        assertEquals(28, q.type)
    }

    @Test fun parsesIpv4Packet() {
        val query = PacketDns.parse(ipv4Udp(dnsQuery("a.com", 1)))
        assertNotNull(query)
        assertEquals(53, query!!.destinationPort)
        assertEquals(0xABCD, query.sourcePort)
    }

    @Test fun blockedResponseNullIp() {
        val dns = dnsQuery("ads.com", 1, edns = true)
        val q = PacketDns.question(dns)!!
        val r = PacketDns.blockedResponse(dns, q, nullIp = true)
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertEquals(1, r[7].toInt())            // ANCOUNT = 1
        assertEquals(0, r[11].toInt())           // ARCOUNT = 0 (OPT eliminat)
        assertEquals(q.end + 16, r.size)
    }

    @Test fun domainSetMatchesSubdomains() {
        val b = DomainSet.Builder()
        listOf("doubleclick.net", "ads.example.org").forEach(b::add)
        val s = b.build()
        assertTrue(s.matches("doubleclick.net"))
        assertTrue(s.matches("googleads.g.doubleclick.net"))
        assertTrue(s.matches("x.ads.example.org"))
        assertFalse(s.matches("example.org"))
        assertFalse(s.matches("notdoubleclick.net"))
        assertEquals("doubleclick.net", s.matchingRule("a.b.doubleclick.net"))
    }

    @Test fun parserFormats() {
        val block = mutableListOf<String>(); val allow = mutableListOf<String>()
        ListParser.parse(sequenceOf(
            "# comment", "0.0.0.0 ads.a.com", "127.0.0.1 localhost", "0.0.0.0 b.com c.com # x",
            "*.wild.com", "plain.com", "||adblock.com^", "@@||good.com^", "||mod.com^\$dnstype=AAAA",
            "!x", "1.2.3.4", "/regex/", "bad domain here",
        ), { block += it }, { allow += it })
        assertEquals(listOf("ads.a.com", "b.com", "c.com", "wild.com", "plain.com", "adblock.com"), block)
        assertEquals(listOf("good.com"), allow)
        assertNull(ListParser.normalize("1.2.3.4"))
    }
}
