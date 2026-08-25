# How does this system work?

Apps like WhatsApp (during voice/video calls), Omegle, or BitTorrent use **P2P (Peer-to-Peer)** technology instead of routing all traffic through a central server. Routing millions of players' gameplay data through central servers would cause high latency (lag) and massive server hosting costs.

### How does the system actually work?

1. Normally, home internet connections don't have open public ports (due to IPv4 address exhaustion and ISPs using CGNAT, you cannot directly open ports from the outside).
2. However, whenever you visit a website or send an outgoing request, your router/ISP temporarily opens an outbound port to the outside world.
3. In this system, the mod connects as if it were visiting a website like Google, but without sending messages; it simply grabs that temporary open port and shares it with your friend, while giving your friend's port to you. It's essentially like saying *"I'm visiting a website"*, and using that opened port to connect directly to your friend.
4. Once the connection is established, the game traffic flows **without any server in between**, directly between your computer and your friend's computer (P2P).

---

### Connection Difference

For example, in similar mods:

`Your computer (Istanbul) -> relay server (Frankfurt) -> your friend's computer (Istanbul)`

In this mod:

`Your computer (Istanbul) -> your friend's computer (Istanbul)`

There is no relay server in between; communication is directly between you and your friend.

## Security

- CodeQL scans are used
- Platforms like CurseForge may scan new versions
- You can see the builds
- Builds are uploaded with GitHub Actions instead of being uploaded manually from my computer


## If you want to read how it works in other languages, click the country icons below.

<p align="center">
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/gb.svg" alt="English" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/tr/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/tr.svg" alt="Turkce" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/de/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/de.svg" alt="Deutsch" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/es/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/es.svg" alt="Espanol" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/fr/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/fr.svg" alt="Francais" width="40">
  </a>
</p>

<p align="center">
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/ja/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/jp.svg" alt="Japanese" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/ko/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/kr.svg" alt="Korean" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/pt-br/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/br.svg" alt="Portuguese Brazil" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/ru/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/ru.svg" alt="Russian" width="40">
  </a>
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="https://github.com/DeveloperKubilay/Safra/blob/assets/languages/zh-cn/how_it_works.md">
    <img src="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/3.5.0/flags/4x3/cn.svg" alt="Chinese" width="40">
  </a>
</p>
