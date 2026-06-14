# Wie funktioniert dieses System?

Apps wie Omegle und WhatsApp kommunizieren normalerweise, ohne einen Server dauerhaft dazwischen zu halten. Dieses System arbeitet mit einer aehnlichen Idee.

Internetanbieter erlauben moeglicherweise keine offenen TCP-Ports, aber UDP-Ports koennen oft genutzt werden.

In diesem Mod wird die Kommunikation des Minecraft-Servers, die normalerweise ueber TCP laeuft, ueber UDP transportiert und der Gegenseite so dargestellt, als waere es TCP.

Dadurch kannst du kostenlos und fluessig mit deinem Freund spielen, ohne dass der Datenverkehr ueber einen Relay-Server geht.

Zum Beispiel bei aehnlichen Mods:

`Dein Computer (Istanbul) -> Relay-Server (Frankfurt) -> Computer deines Freundes (Istanbul)`

In diesem Mod:

`Dein Computer (Istanbul) -> Computer deines Freundes (Istanbul)`

Es gibt keinen Server in der Mitte fuer den Spielverkehr.

Die Kommunikation findet nur zwischen dir und deinem Freund statt.

Dieser Mod verwendet einen Server nur dafuer, damit beide Seiten die IP-Adresse des jeweils anderen finden koennen.

## Sicherheit

- CodeQL-Scans werden verwendet
- Plattformen wie CurseForge koennen neue Versionen scannen
- Du kannst dir die Builds ansehen
- Builds werden mit GitHub Actions hochgeladen statt manuell von meinem Computer
