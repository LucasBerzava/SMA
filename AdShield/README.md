# AdShield — prototip Android pentru proiectul SMA

Aplicație demonstrativă Kotlin/Jetpack Compose care filtrează **cereri DNS IPv4 UDP** printr-un VPN local (`VpnService`). Nu este un adblocker universal.

## Deschidere

1. Instalează Android Studio cu JDK 17 și Android SDK 35.
2. Deschide directorul `AdShield` ca proiect Gradle.
3. Lasă Android Studio să sincronizeze dependențele (necesită internet).
4. Rulează pe un telefon Android 8+; testează pe un dispozitiv propriu.
5. Apasă PORNEȘTE și aprobă permisiunea VPN oferită de Android.
6. Verifică statisticile și adaugă domenii în lista personalizată.

**Notă:** Pachetul nu include Gradle Wrapper sau APK precompilat; în acest mediu nu există Android SDK/Gradle pentru compilare și testare. Dacă Android Studio nu poate importa fără wrapper, creează un proiect Android gol cu aceeași versiune Gradle sau generează wrapper cu Gradle 8.9 (`gradle wrapper --gradle-version 8.9`).

## Ce face

- Setează DNS-ul Android al VPN-ului la `10.111.0.1` și rutează **numai această adresă** în interfața VPN.
- Citește interogări DNS IPv4/UDP, compară domeniul cu lista inclusă și regulile utilizatorului.
- Pentru domeniile blocate răspunde `NXDOMAIN`; pentru celelalte trimite cererea către `1.1.1.1:53` printr-un socket protejat împotriva buclei VPN.
- Stochează local contori și domeniul blocat cel mai recent.

## Limitări importante

- DNS prin TCP, DNS-over-HTTPS, Private DNS, IPv6, cereri fragmentate și formate DNS speciale nu sunt suportate; unele aplicații vor ocoli filtrul sau pot avea probleme de rezoluție. În special, dezactivează Private DNS în setările Android doar pentru testele în care este necesar.
- Unele rețele blochează DNS UDP extern; cererile permise pot eșua. DNS-ul upstream este fix `1.1.1.1`.
- Nu gestionează complet reconectările, cache DNS, jurnalizare complexă sau repornirea automată.
- Statistici simple, nu jurnal complet. Lista încorporată este deliberat scurtă; pentru filtrare serioasă trebuie un mecanism de actualizare și validare a listelor.
- Nu este compilat sau testat pe un dispozitiv în mediul de generare. Este un **punct de pornire funcțional la nivel de implementare**, nu o aplicație certificată ca stabilă.
- Nu include interceptare HTTPS, inspecție de conținut sau bypass al restricțiilor platformelor.

## Pași recomandați pentru o versiune avansată

1. Înlocuiește motorul demonstrativ cu un motor VPN/DNS matur, licențiat corespunzător.
2. Suportă DNS IPv6, TCP, rezolvarea prin upstream configurabil și situațiile cu Private DNS.
3. Adaugă actualizarea sigură a listelor, excepții per aplicație, teste automate și telemetrie locală transparentă.
4. Evaluează comportamentul în rețele diferite și pe Android 12–15.

## Structură

- `MainActivity.kt`: interfața, setările și statisticile.
- `DnsVpnService.kt`: VPN local, interogări DNS și răspunsuri.
- `PacketDns.kt`: parser minimal IPv4/UDP/DNS și construire pachete.
- `FilterStore.kt`: reguli locale și excepții.
- `assets/blocklist.txt`: lista demonstrativă de domenii.
