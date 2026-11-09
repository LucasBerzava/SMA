package com.example.adshield.core

/**
 * Mulțime compactă de domenii: păstrează doar hash-uri de 64 biți, sortate.
 * ~8 octeți/domeniu (300k domenii ≈ 2,4 MB) în loc de ~100 octeți cu HashSet<String>.
 * Probabilitatea unei coliziuni false este neglijabilă (~n / 2^64 per căutare).
 *
 * O regulă "example.com" se aplică și tuturor subdomeniilor (ads.example.com).
 */
class DomainSet private constructor(private val hashes: LongArray) {
    val size: Int get() = hashes.size

    /** true dacă domeniul sau oricare domeniu-părinte este în mulțime. */
    fun matches(domain: String): Boolean {
        if (hashes.isEmpty() || domain.isEmpty()) return false
        var start = 0
        while (true) {
            if (hashes.binarySearch(hash(domain, start, domain.length)) >= 0) return true
            val dot = domain.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
        }
    }

    /** Regula exactă care a potrivit (domeniul sau un părinte), pentru afișare. */
    fun matchingRule(domain: String): String? {
        var start = 0
        while (true) {
            if (hashes.binarySearch(hash(domain, start, domain.length)) >= 0) return domain.substring(start)
            val dot = domain.indexOf('.', start)
            if (dot < 0) return null
            start = dot + 1
        }
    }

    class Builder(initialCapacity: Int = 1024) {
        private var data = LongArray(initialCapacity.coerceAtLeast(16))
        var count = 0
            private set
        fun add(domain: String) {
            if (count == data.size) data = data.copyOf(data.size * 2)
            data[count++] = hash(domain, 0, domain.length)
        }
        fun build(): DomainSet {
            if (count == 0) return EMPTY
            val arr = data.copyOf(count)
            arr.sort()
            var w = 1
            for (r in 1 until arr.size) if (arr[r] != arr[w - 1]) arr[w++] = arr[r]
            return DomainSet(if (w == arr.size) arr else arr.copyOf(w))
        }
    }

    companion object {
        val EMPTY = DomainSet(LongArray(0))

        /** FNV-1a pe 64 de biți + finalizare splitmix64 pentru dispersie bună. */
        fun hash(s: String, from: Int, to: Int): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (i in from until to) {
                h = h xor s[i].code.toLong()
                h *= 0x100000001b3L
            }
            h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
            h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
            return h xor (h ushr 31)
        }
    }
}
