# Safra — Privacy Policy

**Last Updated:** November 14, 2026

> ℹ️ **Official Version & Legal Notice:** The English version of this Privacy Policy is the official and legally binding document. All translations into other languages are provided solely for convenience and informational purposes. In the event of any discrepancies or inconsistencies between a translation and this English text, the English version shall prevail.

Safra is a Minecraft modification that allows players to play together using direct Peer-to-Peer (P2P) connections without renting or setting up a dedicated central game server. We **never sell, rent, or share your data with third parties for advertising, analytics, or commercial purposes.**

---

## 1. What Data We Process & Why We Log It

To operate the mod, match players together, and **protect and legally defend our infrastructure against DDoS attacks and abuse**, only the following technical data is processed:

- **IP Address:** Processed for P2P matchmaking, rate-limiting excessive requests from the same IP, and defending server security in the event of DDoS attacks or abuse; recorded in server access logs.
- **Ephemeral Port Information:** Processed during session matchmaking so the host and joining players can establish a direct connection.
- **Session Code & Optional World Code:** Processed to connect the right players to the right session; session codes are included in server access logs.
- **Relay Requests & RelayId:** Processed when direct P2P hole-punching fails (e.g., strict NAT/CGNAT) in order to establish encrypted TURN / Bedrock Relay fallback and track potential abuse.
- **User-Agent (Safra, Minecraft, Mod Loader, and Java Version):** Logged to verify requests come from official and compatible mod builds, assist with debugging, and distinguish legitimate traffic from bots or attacks.
- **Timestamp & Cloudflare `Ray` ID:** Recorded in server access logs to analyze traffic during DDoS attacks or technical failures.

Server access logs maintained for security and DDoS protection are retained for as long as necessary to ensure system security and legal defense against network attacks.

---

## 2. What We Never Collect or Log

Safra **does not** collect, read, or log any of the following:
- Passwords, Minecraft / Microsoft account credentials, or authentication tokens
- Personal identity or contact information (name, email address, phone number, etc.)
- In-game chat messages, commands, or voice chat audio/contents
- Your list of other installed mods, world save files, inventory data, or any in-game events

---

## 3. P2P Connections, IP Exposure & Code Security (Disclaimer)

- **IP and Port Sharing Is Technically Required:** Safra routes gameplay traffic directly between players (P2P). For the people you share your session code with to connect to your game, your IP address and port must logically and technically be shared with them.
- **Play Only With Trusted Friends:** Due to the nature of P2P networking, peers you connect to or invite into your world can see your IP address. **Safra and its developers cannot be held responsible for IP/port exposure or potential network attacks** resulting from sharing your code with untrusted individuals or posting it publicly. Please play only with friends you trust.
- **Code Predictability:** Session codes are generated using cryptographically secure random generators with 12 characters (or 16 characters in Fixed Code mode), yielding over $32^{12}$ possible combinations. Unless you share your code, it is theoretically and practically impossible for others to guess or discover your session.

---

## 4. Third-Party Infrastructure (Disclaimer)

Your data is never transferred to third parties for commercial purposes; however, the mod must communicate with the following technical infrastructure providers to function:
- **Cloudflare:** Used for DDoS protection on the signaling server and encrypted TURN Relay fallback when direct P2P fails.
- **STUN Servers (Google, Cloudflare, Twilio):** Used solely so the mod can discover your own public IP and port.
- **GitHub / jsDelivr:** Used on startup to check for mod updates and current server configuration (`config.json`).

Any records kept by these third-party services on their own systems and their respective privacy practices are governed by their own privacy policies; **Safra and its developers cannot be held responsible for the data processing practices of these third-party providers.**

---

## 5. Contact

If you have any questions regarding this Privacy Policy, you can reach us at:
- **Discord:** https://discord.gg/NHjBvRxDXP
- **GitHub:** https://github.com/DeveloperKubilay/Safra

## Read this Privacy Policy in other languages

<p align="center">
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/gb.svg" alt="English" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/tr/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/tr.svg" alt="Turkce" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/de/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/de.svg" alt="Deutsch" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/es/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/es.svg" alt="Espanol" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/fr/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/fr.svg" alt="Francais" width="40">
  </a>
</p>

<p align="center">
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/ja/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/jp.svg" alt="Japanese" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/ko/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/kr.svg" alt="Korean" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/pt-br/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/br.svg" alt="Portuguese Brazil" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/ru/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/ru.svg" alt="Russian" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/zh-cn/PRIVACY.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/cn.svg" alt="Chinese" width="40">
  </a>
</p>
