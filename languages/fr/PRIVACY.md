# Safra — Politique de Confidentialité

**Dernière mise à jour :** 14 novembre 2026

> ℹ️ **Avis juridique :** La version officielle et juridiquement contraignante de cette politique de confidentialité est la [version anglaise](https://github.com/DeveloperKubilay/Safra/blob/assets/PRIVACY.md). Les traductions dans d'autres langues sont fournies uniquement à des fins d'information et de commodité. En cas de divergence ou d'incohérence entre une traduction et le texte anglais, la version anglaise prévaudra.

Safra est une modification pour Minecraft permettant aux joueurs de jouer ensemble via des connexions directes de pair à pair (P2P), sans louer ni configurer de serveur de jeu dédié centralisé. Nous **ne vendons, ne louons et ne partageons jamais vos données avec des tiers à des fins publicitaires, analytiques ou commerciales.**

---

## 1. Données que nous traitons et pourquoi nous les enregistrons

Pour que le mod fonctionne techniquement, mette en relation les joueurs et **protège et défende légalement notre infrastructure contre les attaques DDoS et les abus**, seules les données techniques suivantes sont traitées :

- **Adresse IP :** Traitée pour l'appariement P2P, pour limiter les requêtes excessives provenant d'une même adresse IP (rate-limiting) et pour défendre la sécurité du serveur lors d'attaques DDoS ou d'abus ; enregistrée dans les journaux d'accès au serveur.
- **Informations sur le port éphémère :** Traitées lors de la mise en relation pour que l'hôte et les participants puissent établir une connexion directe.
- **Code de session et World Code facultatif :** Traités pour connecter les bons joueurs à la bonne session ; les codes de session figurent dans les logs d'accès.
- **Requêtes de relais et RelayId :** Traités lorsque la traversée directe P2P échoue (ex. NAT strict / CGNAT) afin d'établir un relais chiffré TURN / Bedrock Relay et de détecter les abus éventuels.
- **User-Agent (versions de Safra, Minecraft, Mod Loader et Java) :** Enregistré pour vérifier que les requêtes proviennent de versions officielles et compatibles du mod, faciliter le débogage et distinguer le trafic légitime des bots ou attaques.
- **Horodatage et identifiant Cloudflare `Ray` :** Enregistrés dans les journaux de bord pour analyser le trafic en cas d'attaque DDoS ou de défaillance technique.

Les journaux d'accès au serveur conservés pour la sécurité et la protection DDoS sont conservés aussi longtemps que nécessaire pour assurer la sécurité du système et la défense juridique contre les attaques réseau.

---

## 2. Ce que nous ne collectons et n'enregistrons jamais

Safra **ne** collecte, ne lit et n'enregistre **aucune** des informations suivantes :
- Mots de passe, identifiants de compte Minecraft / Microsoft ou jetons d'authentification
- Informations d'identité personnelle ou de contact (nom, adresse e-mail, numéro de téléphone, etc.)
- Messages de chat en jeu, commandes de console ou contenus du chat vocal
- Votre liste d'autres mods installés, fichiers de sauvegarde de monde (saves), données d'inventaire ou événements en jeu

---

## 3. Connexions P2P, exposition de l'adresse IP et sécurité du code (Clause de non-responsabilité)

- **Le partage de l'IP et du port est techniquement indispensable :** Safra achemine le trafic de jeu directement entre les joueurs (P2P). Pour que les personnes avec lesquelles vous partagez votre code de session puissent rejoindre votre partie, votre adresse IP et votre port doivent obligatoirement leur être transmis.
- **Ne jouez qu'avec des amis de confiance :** En raison de la nature des réseaux P2P, les personnes auxquelles vous vous connectez ou que vous invitez dans votre monde peuvent voir votre adresse IP. **Safra et ses développeurs ne sauraient être tenus responsables d'une exposition d'adresse IP/port ou d'éventuelles attaques réseau** résultant du partage de votre code avec des personnes non fiables ou de sa diffusion publique. Merci de ne jouer qu'avec des personnes de confiance.
- **Sécurité et prévisibilité des codes :** Les codes de session sont générés à l'aide de générateurs aléatoires cryptographiquement sécurisés à 12 caractères (ou 16 caractères en mode code fixe), offrant plus de $32^{12}$ combinaisons possibles. À moins que vous ne partagiez vous-même votre code, il est théoriquement et pratiquement impossible que des tiers devinent ou découvrent votre session.

---

## 4. Infrastructure tierce (Clause de non-responsabilité)

Vos données ne sont jamais transférées à des tiers à des fins commerciales ; toutefois, le mod doit impérativement communiquer avec les infrastructures techniques suivantes :
- **Cloudflare :** Utilisé pour la protection DDoS du serveur de signalement et pour le relais chiffré TURN lorsque le P2P direct échoue.
- **Serveurs STUN (Google, Cloudflare, Twilio) :** Utilisés uniquement pour que le mod puisse identifier votre adresse IP publique et votre port.
- **GitHub / jsDelivr :** Utilisés au lancement pour vérifier les mises à jour du mod et la configuration actuelle du serveur (`config.json`).

Les registres conservés par ces prestataires tiers sur leurs propres systèmes et leurs pratiques de confidentialité sont régis par leurs propres politiques ; **Safra et ses développeurs ne peuvent être tenus responsables des pratiques de traitement des données de ces services tiers.**

---

## 5. Contact

Si vous avez des questions concernant cette Politique de Confidentialité, vous pouvez nous joindre via :
- **Discord :** https://discord.gg/NHjBvRxDXP
- **GitHub :** https://github.com/DeveloperKubilay/Safra
