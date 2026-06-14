# Comment fonctionne ce systeme ?

Des applis comme Omegle et WhatsApp communiquent generalement sans garder un serveur au milieu. Ce systeme fonctionne avec une idee similaire.

Les fournisseurs d'acces a internet peuvent ne pas autoriser l'ouverture de ports TCP, mais ils peuvent autoriser les ports UDP.

Dans ce mod, la communication du serveur Minecraft, qui fonctionne normalement en TCP, est transportee via UDP et presentee a l'autre cote comme si c'etait du TCP.

Cela te permet de jouer gratuitement et de maniere fluide avec ton ami, sans que le trafic passe par un serveur relais.

Par exemple, dans des mods similaires :

`Ton ordinateur (Istanbul) -> serveur relais (Francfort) -> ordinateur de ton ami (Istanbul)`

Dans ce mod :

`Ton ordinateur (Istanbul) -> ordinateur de ton ami (Istanbul)`

Il n'y a pas de serveur au milieu pour le trafic du jeu.

La communication se fait uniquement entre toi et ton ami.

Ce mod utilise seulement un serveur pour permettre aux deux cotes de trouver l'adresse IP de l'autre.

## Securite

- Des scans CodeQL sont utilises
- Des plateformes comme CurseForge peuvent analyser les nouvelles versions
- Tu peux voir les builds
- Les builds sont mises en ligne avec GitHub Actions au lieu d'etre televersees manuellement depuis mon ordinateur
