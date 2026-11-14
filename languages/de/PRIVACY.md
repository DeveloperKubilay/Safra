# Safra — Datenschutzerklärung

**Zuletzt aktualisiert:** 14. November 2026

> ℹ️ **Rechtlicher Hinweis:** Die offizielle und rechtsverbindliche Fassung dieser Datenschutzerklärung ist die [englische Version](https://github.com/DeveloperKubilay/Safra/blob/assets/PRIVACY.md). Übersetzungen in andere Sprachen dienen ausschließlich der Information und Verständlichkeit. Im Falle von Widersprüchen oder Unstimmigkeiten zwischen einer Übersetzung und dem englischen Text ist stets die englische Version maßgebend.

Safra ist eine Minecraft-Modifikation, die es Spielern ermöglicht, über direkte Peer-to-Peer-Verbindungen (P2P) zusammenzuspielen, ohne einen dedizierten zentralen Gameserver mieten oder einrichten zu müssen. Wir **verkaufen, vermieten oder teilen deine Daten niemals mit Dritten für Werbe-, Analyse- oder kommerzielle Zwecke.**

---

## 1. Welche Daten wir verarbeiten & warum wir sie protokollieren

Damit die Modifikation technisch funktioniert, Spieler miteinander verbunden werden können und **unsere Serverinfrastruktur vor DDoS-Angriffen und Missbrauch geschützt und rechtlich verteidigt werden kann**, werden ausschließlich folgende technische Daten verarbeitet:

- **IP-Adresse:** Wird für das P2P-Matchmaking verarbeitet, um übermäßige Anfragen von derselben IP zu begrenzen (Rate-Limiting) und die Serversicherheit im Falle von DDoS-Angriffen oder Missbrauch zu gewährleisten; wird in Serverzugriffsprotokollen erfasst.
- **Flüchtige Port-Informationen:** Werden während der Sitzungsvermittlung verarbeitet, damit Host und beitretende Spieler eine direkte Verbindung aufbauen können.
- **Sitzungscode & optionaler Welt-Code:** Werden verarbeitet, um die richtigen Spieler mit der gewünschten Sitzung zu verbinden; Sitzungscodes werden in den Serverzugriffsprotokollen geführt.
- **Relay-Anfragen & RelayId:** Werden verarbeitet, wenn ein direktes P2P-Hole-Punching fehlschlägt (z. B. bei striktem NAT/CGNAT), um ein verschlüsseltes TURN- / Bedrock-Relay-Fallback aufzubauen und potenziellen Missbrauch nachzuvollziehen.
- **User-Agent (Safra-, Minecraft-, Modloader- und Java-Version):** Wird protokolliert, um sicherzustellen, dass Anfragen von offiziellen und kompatiblen Mod-Builds stammen, zur Fehlerbehebung beizutragen und legitimen Datenverkehr von Bots oder Angriffen zu unterscheiden.
- **Zeitstempel & Cloudflare `Ray`-ID:** Werden in Zugriffsprotokollen gespeichert, um den Datenverkehr bei DDoS-Angriffen oder technischen Störungen analysieren zu können.

Serverzugriffsprotokolle, die zur Systemsicherheit und zum DDoS-Schutz geführt werden, werden so lange aufbewahrt, wie es für die Systemsicherheit und die rechtliche Verteidigung gegen Netzwerkangriffe erforderlich ist.

---

## 2. Was wir niemals erfassen oder protokollieren

Safra erfasst, liest oder protokolliert **keine** der folgenden Daten:
- Passwörter, Minecraft- / Microsoft-Kontodaten oder Authentifizierungs-Token
- Persönliche Identitäts- oder Kontaktdaten (Name, E-Mail-Adresse, Telefonnummer usw.)
- Chat-Nachrichten im Spiel, Konsolenbefehle oder Voice-Chat-Audioinhalte
- Liste deiner anderen installierten Mods, Weltspeicherstände (Saves), Inventardaten oder sonstige In-Game-Ereignisse

---

## 3. P2P-Verbindungen, IP-Offenlegung & Code-Sicherheit (Haftungsausschluss)

- **IP- und Port-Übertragung ist technisch zwingend erforderlich:** Safra leitet den Spielverkehr direkt zwischen den Spielern weiter (P2P). Damit Personen, denen du deinen Sitzungscode gibst, deinem Spiel beitreten können, müssen deine IP-Adresse und dein Port zwangsläufig an diese übermittelt werden.
- **Spiele nur mit vertrauenswürdigen Freunden:** Aufgrund der Funktionsweise von P2P-Netzwerken können Mitspieler, mit denen du dich verbindest oder die du in deine Welt einlädst, deine IP-Adresse sehen. **Safra und die Entwickler können nicht für IP-/Port-Offenlegungen oder potenzielle Netzwerkangriffe verantwortlich gemacht werden**, die daraus resultieren, dass du deinen Code an unzuverlässige Personen weitergibst oder öffentlich teilst. Bitte spiele ausschließlich mit Freunden, denen du vertraust.
- **Code-Sicherheit und Vorhersagbarkeit:** Sitzungscodes werden mit kryptografisch sicheren Zufallsgeneratoren mit 12 Zeichen generiert (bzw. 16 Zeichen im Modus für feste Codes), was über $32^{12}$ mögliche Kombinationen ergibt. Solange du deinen Code nicht weitergibst, ist es theoretisch und praktisch nahezu unmöglich, deine Sitzung zu erraten oder zu finden.

---

## 4. Drittanbieter-Infrastruktur (Haftungsausschluss)

Deine Daten werden niemals zu kommerziellen Zwecken an Dritte weitergegeben; die Mod muss jedoch mit folgenden technischen Infrastrukturen kommunizieren, um ordnungsgemäß zu funktionieren:
- **Cloudflare:** Wird für den DDoS-Schutz des Signalisierungsservers und verschlüsselte TURN-Relay-Übertragungen genutzt, wenn keine direkte P2P-Verbindung möglich ist.
- **STUN-Server (Google, Cloudflare, Twilio):** Werden ausschließlich verwendet, damit die Mod deine eigene öffentliche IP und deinen Port ermitteln kann.
- **GitHub / jsDelivr:** Werden beim Starten genutzt, um nach Mod-Aktualisierungen und der aktuellen Serverkonfiguration (`config.json`) zu suchen.

Alle Aufzeichnungen und Datenschutzpraktiken dieser Drittanbieterdienste unterliegen deren jeweiligen Datenschutzrichtlinien; **Safra und die Entwickler übernehmen keine Verantwortung für die Datenverarbeitungspraktiken dieser Drittanbieter.**

---

## 5. Kontakt

Bei Fragen zu dieser Datenschutzerklärung kannst du uns erreichen über:
- **Discord:** https://discord.gg/NHjBvRxDXP
- **GitHub:** https://github.com/DeveloperKubilay/Safra
