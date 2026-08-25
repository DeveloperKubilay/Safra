# Comment fonctionne ce système ?

Des applications comme WhatsApp (pendant les appels), Omegle ou BitTorrent utilisent la technologie **P2P (Peer-to-Peer / Point à point)** au lieu de router l'ensemble du trafic à travers un serveur central. Faire transiter les données de jeu de millions de joueurs par des serveurs centraux entraînerait une latence élevée (lag) et des coûts de serveur considérables.

### Comment le système fonctionne-t-il exactement ?

1. En temps normal, les connexions internet domestiques ne disposent pas de ports publics ouverts (en raison de l'épuisement des adresses IPv4 et de l'utilisation du CGNAT par les FAI, il est impossible d'ouvrir directement des ports depuis l'extérieur).
2. Cependant, lorsque vous visitez un site web ou envoyez une requête sortante, votre box/FAI ouvre temporairement un port de sortie vers l'extérieur.
3. Avec ce système, le mod se connecte comme s'il allait visiter un site web comme Google, mais sans transmettre de message ; il récupère simplement ce port temporairement ouvert et le transmet à votre ami, tout en vous fournissant le port de votre ami. C'est un peu comme dire *"je vais visiter un site web"* et utiliser ce port ouvert pour vous connecter directement avec votre ami.
4. Une fois la connexion établie, le trafic du jeu s'effectue **sans aucun serveur intermédiaire**, directement entre votre ordinateur et celui de votre ami (P2P).

---

### Différence de Connexion

Par exemple, dans des mods similaires :

`Ton ordinateur (Istanbul) -> serveur relais (Francfort) -> ordinateur de ton ami (Istanbul)`

Dans ce mod :

`Ton ordinateur (Istanbul) -> ordinateur de ton ami (Istanbul)`

Il n'y a aucun serveur relais intermédiaire ; la communication s'effectue uniquement entre vous deux.

## Securite

- Des scans CodeQL sont utilises
- Des plateformes comme CurseForge peuvent analyser les nouvelles versions
- Tu peux voir les builds
- Les builds sont mises en ligne avec GitHub Actions au lieu d'etre televersees manuellement depuis mon ordinateur
