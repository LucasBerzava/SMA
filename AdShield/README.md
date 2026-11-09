# AdShield 1.0 — blocker de reclame pentru Android

Blochează reclamele și trackerele din **aplicații, jocuri și browsere** pe tot telefonul, fără root,
printr-un VPN local care filtrează doar DNS-ul. Restul traficului nu trece prin aplicație, deci
viteza și bateria nu sunt afectate.

## Cum funcționează

1. Aplicația pornește un `VpnService` care rutează **doar** adresele serverului DNS virtual
   (`10.111.0.1` și `fd00:a5a5::1`). Android trimite acolo toate cererile DNS ale aplicațiilor.
2. Fiecare domeniu cerut este comparat cu ~200.000–400.000 de domenii de reclame/trackere
   (căutare binară pe hash-uri de 64 biți: ~0,1 µs/căutare, ~8 octeți/domeniu în RAM).
3. Domeniile blocate primesc `0.0.0.0` / `::` (sau NXDOMAIN); celelalte sunt rezolvate prin
   **DNS criptat (DoH)** la serverul ales (implicit AdGuard DNS, care filtrează și el reclame),
   cu revenire automată pe UDP dacă DoH nu merge.

## Funcții

- **Liste de blocare** alese de utilizator: StevenBlack (inclusă, merge offline), HaGeZi Light/Pro/Pro++,
  OISD Big, AdGuard DNS filter, 1Hosts Lite, Threat Intelligence, DoH bypass, Gambling.
  Formate acceptate: hosts, domenii simple, wildcard (`*.x.com`), Adblock (`||x.com^`, `@@||x.com^`).
  Actualizare automată la 3 zile (cu ETag / If-Modified-Since) și validare înainte de înlocuire.
- **Jurnal live**: fiecare cerere DNS, cu aplicația care a făcut-o (Android 10+). Atingi un domeniu →
  îl blochezi/permiți (sau tot domeniul-părinte).
- **Excluderea aplicațiilor** (ex. jocuri care cer reclame pentru recompense, aplicații bancare).
- **Anti-ocolire**: blochează DoH-ul propriu al browserelor (canary Firefox, endpoint-uri DoH publice).
- **IPv4 + IPv6**, răspunsuri SERVFAIL rapide în loc de timeout, răspunsuri trunchiate corect.
- Statistici (sesiune + total), top aplicații și top domenii blocate.
- Pornire la boot, suport „VPN permanent activ”, buton în setările rapide, notificare cu buton Oprește.
- Avertisment dacă „DNS privat” (strict) e activ, deoarece ocolește filtrul.

## Ce nu poate bloca (limită a oricărui blocker DNS)

- **Reclamele YouTube** și cele din Facebook/Instagram/TikTok: vin de pe aceleași domenii ca
  videoclipurile/postările. Blocarea lor ar opri și conținutul. Alternative: client YouTube fără
  reclame (ReVanced), Firefox + uBlock Origin, Brave.
- Spațiul gol din pagini după blocare (necesită blocker cosmetic în browser).
- Aplicațiile care folosesc DNS criptat propriu, necunoscut listelor (rar).

## Compilare și rulare

1. Android Studio (Ladybug sau mai nou), Android SDK 35.
2. *File › Open* → directorul `AdShield`. Sincronizarea Gradle descarcă dependențele.
3. Rulează pe telefon (Android 8+). Apasă **PORNEȘTE** și aprobă cererea VPN.
4. În tab-ul **Filtre** apasă „Actualizează acum” prima dată (sau „Descarcă acum” din Acasă).
5. Pentru un APK: *Build › Build APK(s)*; varianta `release` e minificată și semnată cu cheia de debug.

Teste unitare (parser DNS, liste, potrivire domenii): `./gradlew test`.

Testare rapidă: deschide un joc gratuit sau https://d3ward.github.io/toolz/adblock și urmărește
tab-ul **Jurnal**. Loguri: `adb logcat -s AdShield AdShield.Lists`.

## Structură

| Fișier | Rol |
|---|---|
| `core/PacketDns.kt` | Parsare IPv4/IPv6/UDP/DNS, construire răspunsuri și pachete (fără dependențe Android) |
| `core/DomainSet.kt` | Mulțime compactă de domenii (hash 64 biți, sortat), potrivire pe subdomenii |
| `core/ListParser.kt` | Citire liste hosts / domenii / wildcard / Adblock |
| `data/FilterEngine.kt` | Decizia de blocare; priorități: excepțiile tale > blocările tale > excepții liste > liste |
| `data/Blocklists.kt` | Catalogul de liste, descărcare, validare, actualizare automată |
| `data/Upstreams.kt` | DoH (RFC 8484), UDP, fallback cu „circuit breaker” |
| `data/Settings.kt`, `data/Stats.kt` | Setări; contori + jurnal în memorie |
| `vpn/DnsVpnService.kt` | VPN-ul: interfața tun, `poll()`, thread-pool, notificare |
| `vpn/BootReceiver.kt`, `vpn/ToggleTileService.kt` | Pornire la boot, buton în setările rapide |
| `ui/*` | Interfața Compose: Acasă, Jurnal, Filtre, Aplicații, Setări |

## Licențe liste

StevenBlack/hosts (MIT) este inclusă în APK. Celelalte liste (HaGeZi — GPL-3.0, OISD, AdGuard — GPL-3.0,
1Hosts — MPL-2.0) sunt descărcate de pe sursele oficiale la cererea utilizatorului, nu sunt redistribuite.
