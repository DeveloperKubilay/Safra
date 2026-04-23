# Safra

Safra is a Fabric mod for Minecraft 1.21.11. The current goal is to let a player open a singleplayer world or dedicated server to friends over a direct UDP P2P tunnel instead of requiring manual TCP port forwarding.

## Current Feature: P2P LAN Sharing

Minecraft's normal "Open to LAN" starts a local TCP listener on a random local port, for example `57764`. Safra adds a P2P layer around that local TCP server:

1. The host opens a world and uses "Open to LAN".
2. Safra starts a UDP socket and asks public STUN servers which public IP:port the NAT assigned to that UDP socket.
3. Safra registers that endpoint with the Cloudflare rendezvous server and prints a short share code such as `ABC123`.
4. The friend's client uses Direct Connect, enables the `P2P` option, and pastes that share code.
5. Safra resolves the code through the rendezvous server, exchanges UDP endpoints, and both sides send UDP punch packets.
6. Safra opens a local loopback TCP proxy on the friend's machine.
7. Minecraft connects to `127.0.0.1:<proxyPort>`, while Safra carries that TCP stream over reliable UDP packets to the host.
8. The host side forwards the UDP tunnel into the real LAN TCP port, for example `57764`.

The local Minecraft LAN port and the public UDP endpoint are intentionally different:

- `57764` is the host machine's local Minecraft TCP port. It is usually not reachable from the internet.
- The public UDP port reported by STUN is stored behind the short rendezvous code. This is the endpoint the friend's Safra client tries to reach.

## UDP Packet Sizing

Safra carries the TCP stream in UDP datagrams with a max payload of `1200` bytes plus an `18` byte Safra header. That keeps each UDP datagram around `1218` bytes before IP/UDP headers, safely below normal MTU limits such as Ethernet `1500` and IPv6 minimum `1280`. Larger Minecraft/TCP data is split across multiple reliable UDP packets.

STUN servers currently used:

- `stun.l.google.com:19302`
- `stun1.l.google.com:19302`
- `stun2.l.google.com:19302`

STUN is only used to discover the public UDP endpoint. Minecraft traffic does not pass through the STUN server or the Cloudflare rendezvous server.

The P2P host does not shut down just because nobody connects. It keeps the UDP mapping alive with STUN refresh packets every 20 seconds and keeps the rendezvous session alive with WebSocket pings. For singleplayer LAN it closes when the player leaves the world, opens another LAN port, or exits the client. For dedicated servers it closes during `SERVER_STOPPING`.

Each rendezvous session also carries a 32-bit tunnel token. The user sees only the short share code, while the token is delivered to the joining client by the rendezvous server. The token is not meant to replace Minecraft authentication, but it prevents random UDP traffic from opening a tunnel without the current share code.

Both players must use the same Safra release. The UDP tunnel protocol version changes when packet format changes, so old jars are not expected to connect to new jars.

Client UI preferences are stored in `config/safra-client.json`:

- Open to LAN `P2P` toggle.
- Open to LAN `Online Mode` toggle.
- Direct Connect `P2P` toggle.

The default rendezvous backend URL is `https://safra.developerkubilay.workers.dev`. Override it with
`-Dsafra.rendezvousUrl=...` or `SAFRA_RENDEZVOUS_URL` when using another `workers.dev` subdomain
or a production custom domain. `SAFRA_SIGNALING_URL` still works as a legacy fallback.

## Implemented Pieces

- `src/main/java/org/developerkubilay/safra/p2p/`
  - `P2pStunClient`: sends STUN binding requests and reads the public UDP endpoint.
  - `P2pHostService`: runs on the world host and forwards UDP tunnel traffic into the local LAN TCP port.
  - `P2pClientProxy`: runs on the joining client and exposes a local TCP proxy for Minecraft.
  - `ReliableTunnelConnection`: adds sequence numbers, ACKs, retransmit, keepalive, and close packets over UDP.
  - `P2pShareCode`: parses short rendezvous codes and legacy `host:port#token` share codes.
  - `SafraRendezvousClient`: keeps the WebSocket control channel open and exchanges UDP endpoints through Cloudflare.

- `src/client/java/org/developerkubilay/safra/client/p2p/`
  - `P2pManager`: owns host/client lifecycle and rewrites P2P connections to loopback.

- `src/main/java/org/developerkubilay/safra/server/`
  - `DedicatedP2pServerManager`: starts P2P hosting after a dedicated server reaches `SERVER_STARTED` and closes it during `SERVER_STOPPING`.

- `src/client/java/org/developerkubilay/safra/mixin/client/`
  - `OpenToLanScreenMixin`: adds the P2P and Online Mode toggles to Open to LAN, starts hosting, copies the share code to clipboard, and prints a clickable copyable chat message plus a normal log line.
  - `DirectConnectScreenMixin`: adds the P2P toggle to Direct Connect.
  - `ConnectScreenMixin`: intercepts P2P Direct Connect attempts and redirects Minecraft to the local proxy.

## Current Test Flow

In IntelliJ, use the normal `runClient` task. If you need two clients, enable "Allow multiple instances" on the run configuration and start `runClient` twice.

Production launcher note: Minecraft 1.21.11 / Feather runs on Java 21. The mod jar must also target Java 21 (`JAVA_21` mixin compatibility and class major version 65). Do not build/release a Java 25-targeted jar, because it crashes at startup on normal launchers.

Host:

1. Start one `runClient` instance for the host.
2. Enter a singleplayer world.
3. Open to LAN with `P2P: ON`.
4. If needed for dev/offline testing, set `Online Mode: OFF` before pressing Start LAN World.
5. Safra copies the share code to clipboard and prints it in chat and logs.

Joiner:

1. Start another `runClient` instance for the joiner.
2. Open Multiplayer.
3. Use Direct Connect.
4. Enable `P2P`.
5. Paste the host's share code.
6. Connect.

Dedicated server:

1. Put the Safra jar on the Fabric dedicated server.
2. Start the server normally.
3. After the server reaches `Done`, Safra starts the UDP P2P host automatically.
4. The console logs `Safra P2P dedicated server opened on local TCP port ... Share code: ...`.
5. Players use Direct Connect, enable `P2P`, and paste that share code.

## Known Limits

This is true direct UDP hole punching, not a traffic relay. It can fail on symmetric or strict NATs, carrier-grade NAT, blocked UDP, or firewalls. STUN and rendezvous only coordinate the public UDP mappings; they do not guarantee every peer can reach every other peer. If we want near-100% connection reliability later, the next step is adding an optional UDP relay fallback.

The `Failed to retrieve profile key pair` / HTTP 401 logs in development clients are Mojang auth/profile-key warnings from offline/dev sessions. They are not the P2P tunnel crash by themselves.

## Release Launcher Troubleshooting

If the mod works in IntelliJ `runClient` but not from a normal launcher jar, first check the Safra P2P logs:

- Joiner should log `Safra P2P client accepted local Minecraft connection ... opening UDP tunnel ...`.
- Host should then log `Safra P2P host received tunnel open ...`.
- If the joiner log appears but the host log never appears, Minecraft auth is not the blocker yet; the UDP packet is being dropped by Windows Firewall, router NAT filtering, CGNAT, or a strict/symmetric NAT.
- If the host receives the tunnel open and Minecraft then disconnects the player, inspect the disconnect text in the Minecraft screen/server log; that is an auth/game protocol issue, not the UDP hole-punch stage.
- Every new Open to LAN session gets a new share code. If the host logs `share-code token is old or wrong`, the joiner reached the host but pasted an old/wrong code.

For P2P LAN sharing, `Online Mode: OFF` is the recommended test mode for now. When P2P is enabled, Safra also disables `preventProxyConnections`, because the tunnel connects to the integrated server through a loopback proxy on the host side.

## Recent Fix Notes

- Fixed a `StackOverflowError` caused by the P2P connection rewrite recursively calling `ConnectScreen.connect`.
- Added automatic clipboard copy for the real P2P share code.
- Made the share code chat component clickable with `copy_to_clipboard`.
- Fixed an `unresolved address` UDP send failure by resolving the P2P share-code host before opening the local proxy.
- Removed P2P from Add Server; P2P joining is Direct Connect only.
- Removed server-list filtering and pinger overrides so the normal Multiplayer server list stays vanilla.
- Added an Open to LAN `Online Mode` toggle and explicit log lines for P2P share codes.
- Fixed release jar startup on normal launchers by targeting Java 21 instead of Java 25.
- P2P hosting now stops when the integrated server LAN port changes, which avoids stale host services between worlds.
- Dedicated servers now auto-start P2P hosting after `SERVER_STARTED` and close UDP hosting during `SERVER_STOPPING`.
- Added persistent client settings in `config/safra-client.json`.
