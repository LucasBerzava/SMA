package com.example.adshield.core

/**
 * Citește liste de blocare în formatele uzuale:
 *  - hosts:     "0.0.0.0 ads.example.com"  /  "127.0.0.1 a.com b.com"
 *  - domenii:   "ads.example.com"  /  "*.ads.example.com"  /  ".ads.example.com"
 *  - adblock:   "||ads.example.com^"  și excepții "@@||good.example.com^"
 * Regulile cu modificatori ($client, $dnstype...), regex sau wildcard în mijloc sunt ignorate.
 */
object ListParser {
    private val IGNORED = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters",
        "ip6-allhosts", "0.0.0.0",
    )
    private val HOST_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0", "255.255.255.255", "fe80::1%lo0", "ff00::0", "ff02::1", "ff02::2", "ff02::3")

    /** Apelează onBlock/onAllow pentru fiecare regulă validă. Întoarce numărul de reguli. */
    fun parse(lines: Sequence<String>, onBlock: (String) -> Unit, onAllow: (String) -> Unit = {}): Int {
        var n = 0
        for (raw in lines) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            val c0 = line[0]
            if (c0 == '#' || c0 == '!' || c0 == '[' || c0 == '/') continue

            if (line.startsWith("@@") || line.startsWith("||")) {
                val allow = line.startsWith("@@")
                if (allow) line = line.substring(2)
                if (!line.startsWith("||")) continue
                line = line.substring(2)
                val caret = line.indexOf('^')
                val domainPart = if (caret >= 0) line.substring(0, caret) else line
                val rest = if (caret >= 0) line.substring(caret + 1) else ""
                if (rest.isNotEmpty() && rest != "|" && rest != "\$important") continue
                val d = normalize(domainPart) ?: continue
                if (allow) onAllow(d) else onBlock(d)
                n++
                continue
            }

            val hash = line.indexOf('#')
            if (hash > 0) line = line.substring(0, hash).trim()
            val tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            if (tokens.size >= 2 && tokens[0] in HOST_IPS) {
                for (t in 1 until tokens.size) {
                    val d = normalize(tokens[t]) ?: continue
                    onBlock(d); n++
                }
            } else if (tokens.size == 1) {
                val d = normalize(tokens[0]) ?: continue
                onBlock(d); n++
            }
        }
        return n
    }

    /** Normalizează un domeniu; întoarce null dacă nu e valid. */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        if (s.startsWith("*.")) s = s.substring(2)
        s = s.trim('.')
        if (s.isEmpty() || s.length > 253 || s in IGNORED) return null
        var hasLetter = false
        for (ch in s) {
            when (ch) {
                in 'a'..'z', '-', '_' -> hasLetter = true
                in '0'..'9', '.' -> {}
                else -> return null
            }
        }
        if (!hasLetter) return null // adrese IP
        if (s.contains("..")) return null
        return s
    }
}
