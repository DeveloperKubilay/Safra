# Wie funktioniert dieses System?

Apps wie WhatsApp (bei Anrufen), Omegle oder BitTorrent nutzen **P2P-Technologie (Peer-to-Peer)**, anstatt den gesamten Datenverkehr über einen zentralen Server zu leiten. Die Spieldaten von Millionen Spielern über zentrale Server zu leiten, würde zu hohen Latenzen (Lags) und enormen Serverkosten führen.

### Wie funktioniert das System genau?

1. Normalerweise verfügen Heim-Internetanschlüsse nicht über offene Ports (da IPv4-Adressen knapp sind und Anbieter CGNAT nutzen, können Ports von außen nicht direkt geöffnet werden).
2. Wenn du jedoch eine Website aufrufst oder eine Anfrage nach außen sendest, öffnet dein Router/Internetanbieter kurzzeitig einen temporären Port nach draußen.
3. In diesem System verbindet sich der Mod so, als würde er eine Website wie Google aufrufen, sendet aber keine Nachrichten; er holt sich lediglich diesen temporär geöffneten Port und gibt ihn an deinen Freund weiter, während du den Port deines Freundes erhältst. Es ist praktisch so, als würde man sagen: *"Ich besuche eine Website"*, und über diesen geöffneten Port verbindet man sich direkt mit seinem Freund.
4. Sobald die Verbindung steht, läuft der gesamte Spielverkehr **ohne Zwischenserver** direkt zwischen deinem Computer und dem deines Freundes (P2P).

---

### Verbindungsunterschied

Zum Beispiel bei aehnlichen Mods:

`Dein Computer (Istanbul) -> Relay-Server (Frankfurt) -> Computer deines Freundes (Istanbul)`

In diesem Mod:

`Dein Computer (Istanbul) -> Computer deines Freundes (Istanbul)`

Es gibt keinen Relay-Server dazwischen; die Kommunikation findet ausschliesslich zwischen euch beiden statt.

## Sicherheit

- CodeQL-Scans werden verwendet
- Plattformen wie CurseForge koennen neue Versionen scannen
- Du kannst dir die Builds ansehen
- Builds werden mit GitHub Actions hochgeladen statt manuell von meinem Computer
