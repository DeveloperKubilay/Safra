# Safra 1.16.x Port Specification & Architecture Notes

**Target Branch:** `1.16.x`  
**Supported MC Profiles:** 1.16, 1.16.1, 1.16.2, 1.16.3, 1.16.4, 1.16.5  
**Runtime Requirement:** Java 8 (Bytecode Major Version 52)  
**Primary Protocol:** ReliableTunnelConnection (v1, 18-byte header) + OkHttp 3.12.13  

---

## 1. Architectural Constraints

### A. Java 8 Runtime Compatibility
The 1.16.x line runs strictly under Java 8 (bytecode 52). Modern language features (records, sealed interfaces, pattern matching `instanceof`, `readNBytes`, `Math.clamp`, etc.) are avoided:
- Internal state carriers use `static final class` structures.
- Buffer manipulations preserve Java 8 buffer covariance by explicit casting where needed.
- Math operations rely on standard `Math.min` / `Math.max` primitives.

### B. Transport Protocol (ReliableTunnelConnection)
Kwik (QUIC protocol implementation) and its underlying TLS 1.3 certificate generation infrastructure require Java 17+ and are not compatible with Java 8. Therefore, the 1.16.x branch retains the production-proven `ReliableTunnelConnection` protocol:
- 18-byte binary v1 header with sequence numbers, ACK/NACK bitmasks, keepalive and flow windowing.
- OkHttp 3.12.13 for REST and SSE rendezvous communication with finite request timeouts (8s) and indefinite SSE read stream timeouts.
- Audio and voice traffic continues over direct UDP STUN/punching via `SafraVoiceClientSocket`.

---

## 2. Features Backported to 1.16.x

### 1. TURN ChannelData & Allocation Refresh Resilience
- **Monotonic Lifetime Tracking:** `P2pTurnDatagramTransport` tracks `allocationExpiresAtNanos` via `System.nanoTime()`. The refresh interval is computed using `Math.max(1L, lifetimeMs - Math.min(lifetimeMs / 2, TimeUnit.SECONDS.toMillis(P2pConstants.TURN_REFRESH_SAFETY_MARGIN_SECONDS)))`. If a refresh attempt fails but the allocation has not yet expired, the transport does not terminate; it schedules a retry in 1 second.
- **ChannelBind & ChannelData Framing:** Supports attributes `ATTR_CHANNEL_NUMBER (0x000C)` and methods `TURN_CHANNEL_BIND_REQUEST (0x0009)` / `TURN_CHANNEL_BIND_RESPONSE (0x0109)`. Wraps and parses 4-byte ChannelData headers with 4-byte alignment padding over UDP, TCP, and TLS.
- **Bounded Datagram Queue:** Replaces unbounded queues with an `ArrayBlockingQueue` bounded to `P2pConstants.DATAGRAM_QUEUE_CAPACITY` (1024 packets).
- **Socket Error Tolerance:** The UDP receive loop applies a 100 ms backoff on transient socket errors rather than crashing immediately.
- **TurnServer Equality:** `P2pTurnCredentials.TurnServer` implements `equals()` and `hashCode()` for deterministic deduplication in server sets.

### 2. worldCode Multiplayer Integration
- **Formatting:** `P2pHostSupport.formatWorldCode(rawName, seed)` sanitizes invalid filesystem characters (`[\\\\/:*?\"<>|]`), caps the world name at 30 characters, and appends a deterministic 6-character hex identifier derived from the world seed (`World [a1b2c3]`).
- **Signaling:** Transmitted via the rendezvous JSON payload (`worldcode`) during host registration and captured on client join.
- **Display:** Fabric and Forge `P2pManager.createRewriteAsync` dynamically resolve and rewrite server entries to display the hosting world name in multiplayer lists.

### 3. Session-Only Host Operator Permissions
- **Non-Persistent Security:** When enabled via the LAN settings UI, the host player is granted level 4 permissions solely in memory. Permissions are never written to disk (`ops.json`).
- **Lifecycle Reset:** Stopping the LAN host or quitting the world invokes `SafraHostPermissions.reset()`, instantly revoking temporary privileges.
- **Hooks:**
  - Fabric: Injects into `MinecraftServer.getPermissionLevel(GameProfile)`.
  - Forge: Injects into `MinecraftServer.func_110455_j` / `getProfilePermissions` with SRG/MCP compatibility.
- **UI & Localization:** Configurable via LAN server settings screens, supported across all 12 localization files.

### 4. Lifecycle Cleanups
- **World Exit Shutdown:** Cleanly halts P2P proxying when leaving a world (`MinecraftClient.disconnect` on Fabric, `ClientPlayerNetworkEvent.LoggedOutEvent` on Forge).
- **Early Connect Protection:** Prevents premature proxy disposal by guarding on `world != null || isIntegratedServerRunning()`.

### 5. Chat Click-to-Copy
- Host share messages format the share code with `ClickEvent.Action.COPY_TO_CLIPBOARD` and `HoverEvent.Action.SHOW_TEXT` in both Fabric and Forge chat pipelines.

---

## 3. Verification

- **Unit Test Suite:** 44/44 tests passing on Temurin OpenJDK 8 (`jdk-8.0.504.1-hotspot`):
  - `P2pHostSupportTest` (6 tests)
  - `P2pTurnChannelTest` (12 tests)
  - `P2pTurnDatagramTransportTest` (5 tests)
  - `P2pTurnMessageTest` (21 tests)
- **Mod Builds:**
  - Fabric JAR: `Safra-fabric-1.16.5-3.2.jar` (all 91 project classes verified at bytecode major version 52)
  - Forge JAR: `Safra-forge-1.16.5-3.2.jar` (all 93 project classes verified at bytecode major version 52)
