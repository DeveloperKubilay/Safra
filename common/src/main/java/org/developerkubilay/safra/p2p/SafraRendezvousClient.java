package org.developerkubilay.safra.p2p;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.developerkubilay.safra.p2p.turn.P2pTurnCredentialClient;
import org.developerkubilay.safra.p2p.turn.P2pTurnCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocketHandshakeException;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class SafraRendezvousClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafraRendezvousClient.class);
    private static final Gson GSON = new Gson();
    private static final int[] CONNECT_RETRY_DELAYS_MS = {0, 350, 1000};

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_TIMEOUT_MS))
        .build();

    private WebSocket webSocket;
    private volatile boolean closed;

    static HostSession startHost(int tcpPort, int tunnelToken, String preferredCode, Collection<InetSocketAddress> publicEndpoints,
                                 Collection<InetSocketAddress> voicePublicEndpoints,
                                 Consumer<InetSocketAddress> punchHandler,
                                 Consumer<InetSocketAddress> voicePunchHandler,
                                 Consumer<InetSocketAddress> relayRequestHandler) throws IOException {
        if (P2pConstants.useApi30Rendezvous()) {
            return Api3Support.startHost(tcpPort, tunnelToken, preferredCode, publicEndpoints, voicePublicEndpoints,
                punchHandler, voicePunchHandler, relayRequestHandler);
        }

        SafraRendezvousClient client = new SafraRendezvousClient();
        HostListener listener = new HostListener(punchHandler, voicePunchHandler, relayRequestHandler);
        try {
            String peerId = "host-" + UUID.randomUUID();
            client.connectHost(peerId, preferredCode, listener);
            String code = listener.codeFuture.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
            if (primaryEndpoint == null) {
                throw new IOException("rendezvous host requires at least one UDP candidate");
            }

            JsonObject ready = new JsonObject();
            ready.addProperty("type", "host:ready");
            ready.add("udp", UdpEndpoint.from(primaryEndpoint).toJson());
            ready.add("udpCandidates", UdpEndpoint.toJsonArray(publicEndpoints));

            JsonObject tunnel = new JsonObject();
            tunnel.addProperty("token", tunnelToken);
            tunnel.addProperty("protocolVersion", Byte.toUnsignedInt(P2pConstants.PROTOCOL_VERSION));
            ready.add("tunnel", tunnel);

            JsonObject metadata = new JsonObject();
            metadata.addProperty("minecraftTcpPort", tcpPort);
            metadata.add("safra", safraMetadata("host"));
            ready.add("metadata", metadata);

            client.send(ready);
            listener.readyFuture.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new HostSession(code, new LegacyHostSessionBackend(client));
        } catch (Exception exception) {
            client.close();
            throw asIOException("Safra rendezvous host setup failed", exception);
        }
    }

    static JoinSession join(String code, Collection<InetSocketAddress> publicEndpoints) throws IOException {
        if (P2pConstants.useApi30Rendezvous()) {
            return Api3Support.join(code, publicEndpoints);
        }

        SafraRendezvousClient client = new SafraRendezvousClient();
        JoinListener listener = new JoinListener();
        try {
            String peerId = "joiner-" + UUID.randomUUID();
            client.connect(webSocketUri("/v3/join/" + encode(code), peerId), listener);
            listener.welcomeFuture.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
            if (primaryEndpoint == null) {
                throw new IOException("rendezvous join requires at least one UDP candidate");
            }

            JsonObject ready = new JsonObject();
            ready.addProperty("type", "join:ready");
            ready.add("udp", UdpEndpoint.from(primaryEndpoint).toJson());
            ready.add("udpCandidates", UdpEndpoint.toJsonArray(publicEndpoints));
            ready.add("metadata", joinMetadata());
            client.send(ready);

            ResolvedHost resolvedHost = listener.resolvedHostFuture.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new JoinSession(code, new LegacyJoinSessionBackend(
                resolvedHost.address(),
                resolvedHost.relayAddress(),
                resolvedHost.tunnelToken(),
                resolvedHost.minecraftTcpPort(),
                client,
                listener
            ));
        } catch (Exception exception) {
            client.close();
            throw asIOException("Safra rendezvous join setup failed", exception);
        }
    }

    static SessionStatus fetchSessionStatus(String code) throws IOException {
        if (P2pConstants.useApi30Rendezvous()) {
            return new SessionStatus(true, false, null);
        }

        if (!P2pConstants.hasRendezvousUrl()) {
            throw new IOException("rendezvous URL is not configured");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(httpUri("/v3/sessions/" + encode(code)))
            .timeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_TIMEOUT_MS))
            .GET();
        String token = P2pConstants.rendezvousToken();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_TIMEOUT_MS))
                .build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("rendezvous status request interrupted", exception);
        }

        if (response.statusCode() == 404) {
            return new SessionStatus(false, false, null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("rendezvous status request failed with HTTP " + response.statusCode());
        }

        JsonObject message;
        try {
            message = new JsonParser().parse(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("rendezvous status response was invalid", exception);
        }

        JsonObject relay = object(message, "relay");
        return new SessionStatus(
            booleanValue(message, "active"),
            "ready".equals(string(message, "relayStatus")) && relay != null && endpoint(object(relay, "udp")) != null,
            relay
        );
    }

    private void publishVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
        InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
        if (primaryEndpoint == null) {
            throw new IOException("rendezvous voice host requires at least one UDP candidate");
        }

        JsonObject ready = new JsonObject();
        ready.addProperty("type", "voice:host-ready");
        ready.add("udp", UdpEndpoint.from(primaryEndpoint).toJson());
        ready.add("udpCandidates", UdpEndpoint.toJsonArray(publicEndpoints));
        send(ready);
    }

    private InetSocketAddress resolveVoice(JoinListener listener, Collection<InetSocketAddress> publicEndpoints) throws IOException {
        InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
        if (primaryEndpoint == null) {
            throw new IOException("rendezvous voice join requires at least one UDP candidate");
        }

        CompletableFuture<ResolvedVoiceHost> future = listener.prepareVoiceFuture();
        JsonObject ready = new JsonObject();
        ready.addProperty("type", "voice:join-ready");
        ready.add("udp", UdpEndpoint.from(primaryEndpoint).toJson());
        ready.add("udpCandidates", UdpEndpoint.toJsonArray(publicEndpoints));
        send(ready);

        try {
            return future.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS).address();
        } catch (Exception exception) {
            throw asIOException("Safra rendezvous voice join setup failed", exception);
        }
    }

    private void connect(URI uri, WebSocket.Listener listener) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt < CONNECT_RETRY_DELAYS_MS.length; attempt++) {
            if (CONNECT_RETRY_DELAYS_MS[attempt] > 0) {
                sleepQuietly(CONNECT_RETRY_DELAYS_MS[attempt]);
            }

            try {
                WebSocket.Builder builder = httpClient.newWebSocketBuilder();
                String token = P2pConstants.rendezvousToken();
                if (!token.isBlank()) {
                    builder.header("Authorization", "Bearer " + token);
                }
                webSocket = builder
                    .buildAsync(uri, listener)
                    .get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (Exception exception) {
                lastException = exception;
                if (!isRetryableConnectFailure(exception) || attempt + 1 >= CONNECT_RETRY_DELAYS_MS.length) {
                    throw exception;
                }
                LOGGER.warn("Safra rendezvous websocket connect retry {} for {} after {}", attempt + 1, uri, describeConnectFailure(exception));
            }
        }

        if (lastException != null) {
            throw lastException;
        }
    }

    private void connectHost(String peerId, String preferredCode, WebSocket.Listener listener) throws Exception {
        String normalizedPreferredCode = P2pShareCode.normalizeRendezvousCode(preferredCode);
        try {
            connect(webSocketUri("/v3/host", peerId, normalizedPreferredCode), listener);
        } catch (Exception exception) {
            if (normalizedPreferredCode != null && isActiveCodeConflict(exception)) {
                connect(webSocketUri("/v3/host", peerId, null), listener);
                return;
            }
            throw exception;
        }
    }

    private void send(JsonObject message) {
        WebSocket socket = webSocket;
        if (socket != null && !closed) {
            socket.sendText(GSON.toJson(message), true);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "safra closed");
        }
    }

    private static URI webSocketUri(String path, String peerId) {
        return webSocketUri(path, peerId, null);
    }

    private static URI webSocketUri(String path, String peerId, String fixedCode) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
        if (base.isBlank()) {
            throw new IllegalStateException("rendezvous URL is not configured");
        }
        URI baseUri = URI.create(base);
        String scheme = switch (baseUri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http" -> "ws";
            case "https" -> "wss";
            case "ws", "wss" -> baseUri.getScheme().toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("unsupported rendezvous URL scheme: " + baseUri.getScheme());
        };

        StringBuilder uri = new StringBuilder(scheme)
            .append("://")
            .append(baseUri.getAuthority())
            .append(path)
            .append("?peerId=")
            .append(encode(peerId));
        if (fixedCode != null && !fixedCode.isBlank()) {
            uri.append("&fixedCode=").append(encode(fixedCode));
        }
        return URI.create(uri.toString());
    }

    private static URI httpUri(String path) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
        if (base.isBlank()) {
            throw new IllegalStateException("rendezvous URL is not configured");
        }

        URI baseUri = URI.create(base);
        String scheme = switch (baseUri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http", "https" -> baseUri.getScheme().toLowerCase(Locale.ROOT);
            case "ws" -> "http";
            case "wss" -> "https";
            default -> throw new IllegalArgumentException("unsupported rendezvous URL scheme: " + baseUri.getScheme());
        };
        return URI.create(scheme + "://" + baseUri.getAuthority() + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static InetSocketAddress preferredEndpoint(Collection<InetSocketAddress> publicEndpoints) {
        if (publicEndpoints == null) {
            return null;
        }

        InetSocketAddress ipv4 = null;
        InetSocketAddress fallback = null;
        for (InetSocketAddress endpoint : publicEndpoints) {
            if (endpoint == null || endpoint.getAddress() == null) {
                continue;
            }

            if (fallback == null) {
                fallback = endpoint;
            }
            if ("ipv4".equals(P2pSockets.addressFamily(endpoint))) {
                ipv4 = endpoint;
                break;
            }
        }

        return ipv4 != null ? ipv4 : fallback;
    }

    private static IOException asIOException(String message, Exception exception) {
        Throwable cause = exception instanceof java.util.concurrent.ExecutionException executionException
            ? executionException.getCause()
            : exception;
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        if (cause instanceof TimeoutException) {
            return new IOException(message + ": timeout", cause);
        }
        if (cause instanceof WebSocketHandshakeException handshakeException) {
            int status = handshakeException.getResponse().statusCode();
            return new IOException(message + ": websocket handshake failed with HTTP " + status, cause);
        }
        return new IOException(message + ": " + cause.getMessage(), cause);
    }

    private static boolean isActiveCodeConflict(Exception exception) {
        Throwable cause = exception instanceof java.util.concurrent.ExecutionException executionException
            ? executionException.getCause()
            : exception;
        return cause instanceof WebSocketHandshakeException handshakeException
            && handshakeException.getResponse().statusCode() == 409;
    }

    private static boolean isRetryableConnectFailure(Exception exception) {
        Throwable cause = exception instanceof java.util.concurrent.ExecutionException executionException
            ? executionException.getCause()
            : exception;
        if (cause instanceof TimeoutException || cause instanceof IOException) {
            return true;
        }
        if (cause instanceof WebSocketHandshakeException handshakeException) {
            int status = handshakeException.getResponse().statusCode();
            return status == 429 || status >= 500;
        }
        return false;
    }

    private static String describeConnectFailure(Exception exception) {
        Throwable cause = exception instanceof java.util.concurrent.ExecutionException executionException
            ? executionException.getCause()
            : exception;
        if (cause instanceof WebSocketHandshakeException handshakeException) {
            return "HTTP " + handshakeException.getResponse().statusCode();
        }
        return cause == null ? "unknown" : cause.toString();
    }

    private static void sleepQuietly(long delayMs) throws Exception {
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static JsonObject joinMetadata() {
        JsonObject metadata = new JsonObject();
        metadata.add("safra", safraMetadata("join"));
        return metadata;
    }

    private static JsonObject safraMetadata(String role) {
        JsonObject safra = new JsonObject();
        safra.addProperty("role", role);
        safra.addProperty("minecraftVersion", SafraBuildInfo.minecraftVersion());
        safra.addProperty("loader", SafraBuildInfo.loaderName());
        safra.addProperty("loaderVersion", SafraBuildInfo.loaderVersion());
        safra.addProperty("modVersion", SafraBuildInfo.modVersion());
        safra.addProperty("protocolVersion", Byte.toUnsignedInt(P2pConstants.PROTOCOL_VERSION));
        return safra;
    }

    private abstract static class JsonListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                try {
                    JsonObject message = new JsonParser().parse(buffer.toString()).getAsJsonObject();
                    handle(message);
                } catch (RuntimeException exception) {
                    fail(exception);
                } finally {
                    buffer.setLength(0);
                }
            }

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            fail(new IOException("rendezvous websocket closed: " + statusCode + " " + reason));
            return CompletableFuture.completedFuture(null);
        }

        protected abstract void handle(JsonObject message);

        protected void fail(Throwable throwable) {
        }
    }

    private static final class HostListener extends JsonListener {
        private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
        private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
        private final Consumer<InetSocketAddress> punchHandler;
        private final Consumer<InetSocketAddress> voicePunchHandler;
        private final Consumer<InetSocketAddress> relayRequestHandler;

        private HostListener(Consumer<InetSocketAddress> punchHandler, Consumer<InetSocketAddress> voicePunchHandler,
                             Consumer<InetSocketAddress> relayRequestHandler) {
            this.punchHandler = punchHandler;
            this.voicePunchHandler = voicePunchHandler;
            this.relayRequestHandler = relayRequestHandler;
        }

        @Override
        protected void handle(JsonObject message) {
            String type = string(message, "type");
            if ("server:welcome".equals(type)) {
                codeFuture.complete(string(message, "code"));
                return;
            }

            if ("server:host-ready".equals(type)) {
                readyFuture.complete(null);
                return;
            }

            if ("server:joiner-ready".equals(type) || "server:punch-start".equals(type)) {
                InetSocketAddress endpoint = endpoint(message.getAsJsonObject("udp"));
                if (endpoint != null) {
                    punchHandler.accept(endpoint);
                }
                return;
            }

            if ("server:voice-joiner-ready".equals(type)) {
                InetSocketAddress endpoint = endpoint(message.getAsJsonObject("udp"));
                if (endpoint != null) {
                    voicePunchHandler.accept(endpoint);
                }
                return;
            }

            if ("server:voice-error".equals(type)) {
                LOGGER.debug("Safra voice host rendezvous warning: {}", string(message, "message"));
                return;
            }

            if ("server:relay-requested".equals(type)) {
                InetSocketAddress joinerRelayAddress = relayEndpoint(object(message, "joinerRelay"));
                relayRequestHandler.accept(joinerRelayAddress);
                return;
            }

            if ("server:error".equals(type)) {
                fail(new IOException(string(message, "message")));
            }
        }

        @Override
        protected void fail(Throwable throwable) {
            codeFuture.completeExceptionally(throwable);
            readyFuture.completeExceptionally(throwable);
        }
    }

    private static final class JoinListener extends JsonListener {
        private final CompletableFuture<Void> welcomeFuture = new CompletableFuture<>();
        private final CompletableFuture<ResolvedHost> resolvedHostFuture = new CompletableFuture<>();
        private volatile CompletableFuture<ResolvedRelay> resolvedRelayFuture;
        private volatile CompletableFuture<ResolvedVoiceHost> resolvedVoiceFuture;

        CompletableFuture<ResolvedRelay> prepareRelayFuture() {
            CompletableFuture<ResolvedRelay> future = new CompletableFuture<>();
            resolvedRelayFuture = future;
            return future;
        }

        CompletableFuture<ResolvedVoiceHost> prepareVoiceFuture() {
            CompletableFuture<ResolvedVoiceHost> future = new CompletableFuture<>();
            resolvedVoiceFuture = future;
            return future;
        }

        @Override
        protected void handle(JsonObject message) {
            String type = string(message, "type");
            if ("server:welcome".equals(type)) {
                welcomeFuture.complete(null);
                return;
            }

            if ("server:host-ready".equals(type)) {
                InetSocketAddress endpoint = endpoint(message.getAsJsonObject("udp"));
                InetSocketAddress relayAddress = relayEndpoint(object(message, "relay"));
                int token = tunnelToken(message.getAsJsonObject("tunnel"));
                if (endpoint == null) {
                    fail(new IOException("rendezvous host endpoint is missing"));
                    return;
                }
                JsonObject metadata = message.getAsJsonObject("metadata");
                int minecraftTcpPort = minecraftTcpPort(metadata);
                resolvedHostFuture.complete(new ResolvedHost(endpoint, relayAddress, token, minecraftTcpPort));
                if (relayAddress != null) {
                    completeRelayFuture(new ResolvedRelay(relayAddress, token, null));
                }
                return;
            }

            if ("server:relay-ready".equals(type)) {
                InetSocketAddress relayAddress = relayEndpoint(object(message, "relay"));
                int token = tunnelToken(message.getAsJsonObject("tunnel"));
                if (relayAddress == null) {
                    completeRelayFutureExceptionally(new IOException("rendezvous relay endpoint is missing"));
                    return;
                }
                completeRelayFuture(new ResolvedRelay(relayAddress, token, null));
                return;
            }

            if ("server:relay-failed".equals(type)) {
                completeRelayFutureExceptionally(new IOException(string(message, "message")));
                return;
            }

            if ("server:voice-host-ready".equals(type)) {
                InetSocketAddress endpoint = endpoint(message.getAsJsonObject("udp"));
                if (endpoint == null) {
                    CompletableFuture<ResolvedVoiceHost> future = resolvedVoiceFuture;
                    if (future != null) {
                        future.completeExceptionally(new IOException("rendezvous voice host endpoint is missing"));
                    }
                    return;
                }

                CompletableFuture<ResolvedVoiceHost> future = resolvedVoiceFuture;
                if (future != null) {
                    future.complete(new ResolvedVoiceHost(endpoint));
                    resolvedVoiceFuture = null;
                }
                return;
            }

            if ("server:voice-error".equals(type)) {
                CompletableFuture<ResolvedVoiceHost> future = resolvedVoiceFuture;
                if (future != null) {
                    future.completeExceptionally(new IOException(string(message, "message")));
                    resolvedVoiceFuture = null;
                }
                return;
            }

            if ("server:session-closed".equals(type)) {
                fail(new IOException("rendezvous session closed: " + string(message, "reason")));
                return;
            }

            if ("server:error".equals(type)) {
                fail(new IOException(string(message, "message")));
            }
        }

        @Override
        protected void fail(Throwable throwable) {
            welcomeFuture.completeExceptionally(throwable);
            resolvedHostFuture.completeExceptionally(throwable);
            completeRelayFutureExceptionally(throwable);
            CompletableFuture<ResolvedVoiceHost> future = resolvedVoiceFuture;
            if (future != null) {
                future.completeExceptionally(throwable);
            }
        }

        private void completeRelayFuture(ResolvedRelay relay) {
            CompletableFuture<ResolvedRelay> future = resolvedRelayFuture;
            if (future != null) {
                future.complete(relay);
                resolvedRelayFuture = null;
            }
        }

        private void completeRelayFutureExceptionally(Throwable throwable) {
            CompletableFuture<ResolvedRelay> future = resolvedRelayFuture;
            if (future != null) {
                future.completeExceptionally(throwable);
                resolvedRelayFuture = null;
            }
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static boolean booleanValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }

    private static InetSocketAddress endpoint(JsonObject object) {
        if (object == null) {
            return null;
        }

        String host = string(object, "host");
        int port = integer(object.get("port"), 0);
        if (host.isBlank() || port < 1 || port > 65535) {
            return null;
        }

        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (IOException exception) {
            LOGGER.debug("Could not resolve rendezvous UDP endpoint {}:{}", host, port, exception);
            return null;
        }
    }

    private static InetSocketAddress relayEndpoint(JsonObject relayObject) {
        return relayObject == null ? null : endpoint(object(relayObject, "udp"));
    }

    private static int tunnelToken(JsonObject object) {
        if (object == null) {
            return 0;
        }

        return integer(object.get("token"), 0);
    }

    private static int minecraftTcpPort(JsonObject object) {
        if (object == null) {
            return 0;
        }

        return integer(object.get("minecraftTcpPort"), 0);
    }

    private static int integer(JsonElement element, int fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }

        try {
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt();
            }
            return Integer.parseUnsignedInt(element.getAsString(), 36);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    interface HostSessionBackend extends AutoCloseable {
        void publishVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException;

        void publishRelay(Collection<InetSocketAddress> publicEndpoints, String mode) throws IOException;

        void publishRelayFailure(String mode, String message);

        default P2pTurnCredentials consumePendingRelayCredentials() {
            return null;
        }

        @Override
        void close();
    }

    interface JoinSessionBackend extends AutoCloseable {
        InetSocketAddress hostAddress(boolean relayPreferred);

        int tunnelToken();

        int hostTcpPort();

        InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException;

        ResolvedRelay requestRelayFallback(Collection<InetSocketAddress> relayEndpoints) throws IOException;

        @Override
        void close();
    }

    private static final class LegacyHostSessionBackend implements HostSessionBackend {
        private final SafraRendezvousClient client;

        private LegacyHostSessionBackend(SafraRendezvousClient client) {
            this.client = client;
        }

        @Override
        public void publishVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            client.publishVoice(publicEndpoints);
        }

        @Override
        public void publishRelay(Collection<InetSocketAddress> publicEndpoints, String mode) throws IOException {
            InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
            if (primaryEndpoint == null) {
                throw new IOException("rendezvous relay host requires at least one UDP candidate");
            }

            JsonObject relay = new JsonObject();
            relay.addProperty("type", "host:relay-ready");
            relay.add("udp", UdpEndpoint.from(primaryEndpoint).toJson());
            relay.add("udpCandidates", UdpEndpoint.toJsonArray(publicEndpoints));
            relay.addProperty("mode", mode == null || mode.isBlank() ? "auto" : mode);
            client.send(relay);
        }

        @Override
        public void publishRelayFailure(String mode, String message) {
            JsonObject relay = new JsonObject();
            relay.addProperty("type", "host:relay-failed");
            relay.addProperty("mode", mode == null || mode.isBlank() ? "auto" : mode);
            if (message != null && !message.isBlank()) {
                relay.addProperty("message", message);
            }
            client.send(relay);
        }

        @Override
        public P2pTurnCredentials consumePendingRelayCredentials() {
            return null;
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private static final class LegacyJoinSessionBackend implements JoinSessionBackend {
        private final InetSocketAddress hostAddress;
        private final InetSocketAddress relayAddress;
        private final int tunnelToken;
        private final int hostTcpPort;
        private final SafraRendezvousClient client;
        private final JoinListener listener;

        private LegacyJoinSessionBackend(InetSocketAddress hostAddress, InetSocketAddress relayAddress, int tunnelToken, int hostTcpPort,
                                         SafraRendezvousClient client, JoinListener listener) {
            this.hostAddress = hostAddress;
            this.relayAddress = relayAddress;
            this.tunnelToken = tunnelToken;
            this.hostTcpPort = hostTcpPort;
            this.client = client;
            this.listener = listener;
        }

        @Override
        public InetSocketAddress hostAddress(boolean relayPreferred) {
            if (relayPreferred && relayAddress != null) {
                return relayAddress;
            }
            return relayPreferred ? null : hostAddress;
        }

        @Override
        public int tunnelToken() {
            return tunnelToken;
        }

        @Override
        public int hostTcpPort() {
            return hostTcpPort;
        }

        @Override
        public InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            return client.resolveVoice(listener, publicEndpoints);
        }

        @Override
        public ResolvedRelay requestRelayFallback(Collection<InetSocketAddress> relayEndpoints) throws IOException {
            if (relayAddress != null) {
                return new ResolvedRelay(relayAddress, tunnelToken, null);
            }

            CompletableFuture<ResolvedRelay> future = listener.prepareRelayFuture();
            JsonObject request = new JsonObject();
            request.addProperty("type", "join:direct-failed");
            if (relayEndpoints != null && !relayEndpoints.isEmpty()) {
                InetSocketAddress primaryRelayEndpoint = preferredEndpoint(relayEndpoints);
                if (primaryRelayEndpoint != null) {
                    JsonObject relay = new JsonObject();
                    relay.add("udp", UdpEndpoint.from(primaryRelayEndpoint).toJson());
                    relay.add("udpCandidates", UdpEndpoint.toJsonArray(relayEndpoints));
                    relay.addProperty("mode", "auto");
                    relay.addProperty("status", "ready");
                    request.add("relay", relay);
                }
            }
            client.send(request);
            try {
                return future.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                throw asIOException("Safra rendezvous relay fallback failed", exception);
            }
        }

        private String codeForLog() {
            return hostAddress == null ? "-" : hostAddress.toString();
        }

        @Override
        public void close() {
            client.close();
        }
    }

    static final class HostSession implements AutoCloseable {
        private final String code;
        private final HostSessionBackend backend;

        HostSession(String code, HostSessionBackend backend) {
            this.code = code;
            this.backend = backend;
        }

        String code() {
            return code;
        }

        void publishVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            backend.publishVoice(publicEndpoints);
        }

        void publishRelay(Collection<InetSocketAddress> publicEndpoints, String mode) throws IOException {
            backend.publishRelay(publicEndpoints, mode);
        }

        void publishRelayFailure(String mode, String message) {
            backend.publishRelayFailure(mode, message);
        }

        P2pTurnCredentials consumePendingRelayCredentials() {
            return backend.consumePendingRelayCredentials();
        }

        @Override
        public void close() {
            backend.close();
        }
    }

    static final class JoinSession implements AutoCloseable {
        private final String code;
        private final JoinSessionBackend backend;

        JoinSession(String code, JoinSessionBackend backend) {
            this.code = code;
            this.backend = backend;
        }

        String code() {
            return code;
        }

        InetSocketAddress hostAddress(boolean relayPreferred) {
            return backend.hostAddress(relayPreferred);
        }

        int tunnelToken() {
            return backend.tunnelToken();
        }

        int hostTcpPort() {
            return backend.hostTcpPort();
        }

        InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            return backend.resolveVoice(publicEndpoints);
        }

        ResolvedRelay requestRelayFallback(Collection<InetSocketAddress> relayEndpoints) throws IOException {
            return backend.requestRelayFallback(relayEndpoints);
        }

        @Override
        public void close() {
            backend.close();
        }
    }

    private static final class Api3Support {
        private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_TIMEOUT_MS))
            .build();

        private Api3Support() {
        }

        static HostSession startHost(int tcpPort, int tunnelToken, String preferredCode, Collection<InetSocketAddress> publicEndpoints,
                                     Collection<InetSocketAddress> voicePublicEndpoints,
                                     Consumer<InetSocketAddress> punchHandler,
                                     Consumer<InetSocketAddress> voicePunchHandler,
                                     Consumer<InetSocketAddress> relayRequestHandler) throws IOException {
            InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
            if (primaryEndpoint == null && !P2pConstants.useApi30Rendezvous()) {
                throw new IOException("Safra host en az bir UDP ucu istiyor");
            }

            Api3HostSessionBackend backend = new Api3HostSessionBackend(punchHandler, voicePunchHandler, relayRequestHandler);
            try {
                String code = backend.open(
                    tcpPort,
                    tunnelToken,
                    preferredCode,
                    primaryEndpoint,
                    preferredEndpoint(voicePublicEndpoints)
                );
                return new HostSession(code, backend);
            } catch (IOException exception) {
                backend.close();
                throw exception;
            }
        }

        static JoinSession join(String code, Collection<InetSocketAddress> publicEndpoints) throws IOException {
            InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
            if (primaryEndpoint == null) {
                throw new IOException("Safra join en az bir UDP ucu istiyor");
            }

            Api3JoinSessionBackend backend = new Api3JoinSessionBackend(code);
            backend.open(primaryEndpoint);
            return new JoinSession(code, backend);
        }

        private static final class Api3HostSessionBackend implements HostSessionBackend {
            private final Consumer<InetSocketAddress> punchHandler;
            private final Consumer<InetSocketAddress> voicePunchHandler;
            private final Consumer<InetSocketAddress> relayRequestHandler;
            private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
            private volatile InputStream stream;
            private volatile Thread streamThread;
            private volatile Thread relayRequestThread;
            private volatile boolean closed;
            private volatile String code;
            private volatile InetSocketAddress lastJoinerAddress;
            private volatile P2pTurnCredentials pendingRelayCredentials;
            private volatile boolean relayAssigned;
            private volatile boolean relayRequestQueued;

            private Api3HostSessionBackend(Consumer<InetSocketAddress> punchHandler,
                                           Consumer<InetSocketAddress> voicePunchHandler,
                                           Consumer<InetSocketAddress> relayRequestHandler) {
                this.punchHandler = punchHandler;
                this.voicePunchHandler = voicePunchHandler;
                this.relayRequestHandler = relayRequestHandler;
            }

            private String open(int tcpPort, int tunnelToken, String preferredCode, InetSocketAddress endpoint,
                                InetSocketAddress voiceEndpoint) throws IOException {
                JsonObject request = new JsonObject();
                if (endpoint != null && !P2pConstants.forceHostFailSafeRelay()) {
                    request.add("network", toNetwork(endpoint));
                }
                if (voiceEndpoint != null) {
                    request.add("voicechat", toNetwork(voiceEndpoint));
                }
                request.addProperty("tunnelToken", tunnelToken);
                request.addProperty("minecraftTcpPort", tcpPort);
                if (preferredCode != null && !preferredCode.isBlank()) {
                    request.addProperty("code", preferredCode);
                }

                HttpRequest httpRequest = requestBuilder(httpUri("/session-create"))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                    .build();

                HttpResponse<InputStream> response;
                try {
                    response = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Safra host istegi kesildi", exception);
                }

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Safra host istegi HTTP " + response.statusCode() + " dondu");
                }

                stream = response.body();
                streamThread = P2pRuntime.start("safra-rendezvous-host-events", this::readEvents);
                try {
                    return codeFuture.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception exception) {
                    throw asIOException("Safra host kurulumu basarisiz", exception);
                }
            }

            @Override
            public void publishVoice(Collection<InetSocketAddress> publicEndpoints) {
            }

            @Override
            public void publishRelay(Collection<InetSocketAddress> publicEndpoints, String mode) throws IOException {
                InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
                if (primaryEndpoint == null || code == null || code.isBlank()) {
                    throw new IOException("Safra relay host etkin oturum ve UDP ucu istiyor");
                }

                JsonObject request = new JsonObject();
                request.addProperty("code", code);
                request.add("network", toNetwork(primaryEndpoint));
                HttpResponse<String> response = sendText(requestBuilder(httpUri("/relay-accept"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                    .build());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Safra relay publish HTTP " + response.statusCode() + " dondu");
                }
            }

            @Override
            public void publishRelayFailure(String mode, String message) {
                LOGGER.warn("Safra relay publish failed mode={} message={}",
                    mode == null || mode.isBlank() ? "auto" : mode,
                    message == null ? "" : message);
            }

            @Override
            public P2pTurnCredentials consumePendingRelayCredentials() {
                P2pTurnCredentials credentials = pendingRelayCredentials;
                pendingRelayCredentials = null;
                return credentials;
            }

            @Override
            public void close() {
                closed = true;
                closeQuietly(stream);
                if (streamThread != null) {
                    streamThread.interrupt();
                }
                if (relayRequestThread != null) {
                    relayRequestThread.interrupt();
                }
            }

            private void readEvents() {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String event = "";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!event.isBlank()) {
                                handleEvent(event, parseApi3Object(data.toString(), "Safra event payload gecersiz"));
                            }
                            event = "";
                            data.setLength(0);
                            continue;
                        }
                        if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (data.length() > 0) {
                                data.append('\n');
                            }
                            data.append(line.substring(5).trim());
                        }
                    }
                } catch (IOException exception) {
                    if (!closed) {
                        codeFuture.completeExceptionally(exception);
                        LOGGER.debug("Safra host event stream kapandi: {}", exception.toString());
                    }
                }
            }

            private void handleEvent(String event, JsonObject data) throws IOException {
                if ("session-created".equals(event)) {
                    code = string(data, "code");
                    codeFuture.complete(code);
                    if (booleanValue(data, "relayRequired")) {
                        queueRelayRequest();
                    }
                    return;
                }
                if ("session-joined".equals(event)) {
                    lastJoinerAddress = fromNetwork(array(data, "host"));
                    if (lastJoinerAddress != null) {
                        punchHandler.accept(lastJoinerAddress);
                        if (relayAssigned) {
                            relayRequestHandler.accept(lastJoinerAddress);
                        }
                    }
                    InetSocketAddress voiceJoiner = fromNetwork(array(data, "voiceHost"));
                    if (voiceJoiner != null) {
                        voicePunchHandler.accept(voiceJoiner);
                    }
                    return;
                }
                if ("voicechat-updated".equals(event)) {
                    InetSocketAddress voiceJoiner = fromNetwork(array(data, "voiceHost"));
                    if (voiceJoiner != null) {
                        voicePunchHandler.accept(voiceJoiner);
                    }
                    return;
                }
                if ("relay-assigned".equals(event)) {
                    pendingRelayCredentials = P2pTurnCredentialClient.parse(wrapIceServers(data));
                    relayAssigned = true;
                    relayRequestHandler.accept(lastJoinerAddress);
                }
            }

            private synchronized void queueRelayRequest() {
                if (relayRequestQueued || code == null || code.isBlank() || closed) {
                    return;
                }
                relayRequestQueued = true;
                relayRequestThread = P2pRuntime.start("safra-rendezvous-host-relay-request", this::requestRelayAssignment);
            }

            private void requestRelayAssignment() {
                JsonObject request = new JsonObject();
                request.addProperty("code", code);
                HttpRequest httpRequest = requestBuilder(httpUri("/relay-request"))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                    .build();

                try {
                    HttpResponse<InputStream> response = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IOException("Safra host relay istegi HTTP " + response.statusCode() + " dondu");
                    }

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        while (!closed && reader.readLine() != null) {
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (IOException exception) {
                    if (!closed) {
                        LOGGER.warn("Safra host relay istegi patladi: {}", exception.toString());
                    }
                }
            }
        }

        private static final class Api3JoinSessionBackend implements JoinSessionBackend {
            private final String code;
            private InetSocketAddress joinAddress;
            private InetSocketAddress hostAddress;
            private InetSocketAddress voiceAddress;
            private InetSocketAddress relayAddress;
            private P2pTurnCredentials relayCredentials;

            private Api3JoinSessionBackend(String code) {
                this.code = code;
            }

            private void open(InetSocketAddress endpoint) throws IOException {
                joinAddress = endpoint;
                JsonObject request = new JsonObject();
                request.addProperty("code", code);
                if (endpoint != null) {
                    request.add("network", toNetwork(endpoint));
                }
                HttpResponse<String> response = sendText(requestBuilder(httpUri("/session-join"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                    .build());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Safra join istegi HTTP " + response.statusCode() + " dondu");
                }

                JsonObject json = parseApi3Object(response.body(), "Safra join cevabi gecersiz");
                hostAddress = fromNetwork(array(json, "host"));
                voiceAddress = fromNetwork(array(json, "voiceHost"));
                JsonObject relay = object(json, "relay");
                relayAddress = relayNetwork(relay);
                relayCredentials = relayCredentials(relay);
                if (hostAddress == null && relayAddress == null) {
                    throw new IOException("Safra join cevabinda host adresi yok");
                }
            }

            @Override
            public InetSocketAddress hostAddress(boolean relayPreferred) {
                if (relayPreferred && relayAddress != null) {
                    return relayAddress;
                }
                return relayPreferred ? null : hostAddress;
            }

            @Override
            public int tunnelToken() {
                return P2pShareCode.rendezvousTunnelToken(code);
            }

            @Override
            public int hostTcpPort() {
                return 0;
            }

            @Override
            public InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
                InetSocketAddress localVoiceEndpoint = preferredEndpoint(publicEndpoints);
                if (localVoiceEndpoint != null) {
                    publishVoiceUpdate(localVoiceEndpoint);
                }

                if (voiceAddress != null) {
                    return voiceAddress;
                }

                long deadline = System.currentTimeMillis() + P2pConstants.RENDEZVOUS_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    sleepQuietly(200L);
                    refreshHostState(joinAddress);
                    if (voiceAddress != null) {
                        return voiceAddress;
                    }
                }

                throw new IOException("Safra voice endpoint zamaninda gelmedi");
            }

            @Override
            public ResolvedRelay requestRelayFallback(Collection<InetSocketAddress> relayEndpoints) throws IOException {
                if (relayAddress != null) {
                    InetSocketAddress localRelayEndpoint = preferredEndpoint(relayEndpoints);
                    if (localRelayEndpoint != null) {
                        refreshHostState(localRelayEndpoint);
                    }
                    return new ResolvedRelay(relayAddress, P2pShareCode.rendezvousTunnelToken(code), relayCredentials);
                }

                JsonObject request = new JsonObject();
                request.addProperty("code", code);
                HttpRequest httpRequest = requestBuilder(httpUri("/relay-request"))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                    .build();

                HttpResponse<InputStream> response;
                try {
                    response = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Safra relay istegi kesildi", exception);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Safra relay istegi HTTP " + response.statusCode() + " dondu");
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String event = "";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!event.isBlank()) {
                                JsonObject json = parseApi3Object(data.toString(), "Safra relay payload gecersiz");
                                if ("relay-accepted".equals(event)) {
                                    JsonObject relay = object(json, "relay");
                                    relayAddress = relay != null ? relayNetwork(relay) : fromNetwork(array(json, "network"));
                                    relayCredentials = relay != null ? relayCredentials(relay) : relayCredentials;
                                    if (relayAddress == null) {
                                        throw new IOException("Safra relay cevabinda network yok");
                                    }
                                    return new ResolvedRelay(relayAddress, P2pShareCode.rendezvousTunnelToken(code), relayCredentials);
                                }
                                if ("relay-timeout".equals(event)) {
                                    throw new IOException(string(json, "message"));
                                }
                            }
                            event = "";
                            data.setLength(0);
                            continue;
                        }
                        if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (data.length() > 0) {
                                data.append('\n');
                            }
                            data.append(line.substring(5).trim());
                        }
                    }
                }

                throw new IOException("Safra relay event stream kapandi");
            }

            @Override
            public void close() {
            }

            private void refreshHostState(InetSocketAddress endpoint) throws IOException {
                if (endpoint == null) {
                    return;
                }

                JsonObject request = new JsonObject();
                request.addProperty("code", code);
                if (endpoint != null) {
                    request.add("network", toNetwork(endpoint));
                }
                HttpResponse<String> response = sendText(requestBuilder(httpUri("/session-join"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                    .build());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return;
                }

                JsonObject json = parseApi3Object(response.body(), "Safra join refresh cevabi gecersiz");
                InetSocketAddress refreshedVoice = fromNetwork(array(json, "voiceHost"));
                if (refreshedVoice != null) {
                    voiceAddress = refreshedVoice;
                }
                JsonObject relay = object(json, "relay");
                InetSocketAddress refreshedRelay = relayNetwork(relay);
                if (refreshedRelay != null) {
                    relayAddress = refreshedRelay;
                    P2pTurnCredentials refreshedCredentials = relayCredentials(relay);
                    if (refreshedCredentials != null) {
                        relayCredentials = refreshedCredentials;
                    }
                }
            }

            private void publishVoiceUpdate(InetSocketAddress endpoint) throws IOException {
                JsonObject request = new JsonObject();
                request.addProperty("code", code);
                request.add("voicechat", toNetwork(endpoint));
                HttpResponse<String> response = sendText(requestBuilder(httpUri("/voicechat-update"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                    .build());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Safra voice update istegi HTTP " + response.statusCode() + " dondu");
                }
            }
        }

        private static HttpRequest.Builder requestBuilder(URI uri) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_TIMEOUT_MS));
            String token = P2pConstants.rendezvousToken();
            if (!token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            return builder;
        }

        private static HttpResponse<String> sendText(HttpRequest request) throws IOException {
            try {
                return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Safra istegi kesildi", exception);
            }
        }

        private static JsonObject wrapIceServers(JsonObject turnCredentials) {
            JsonObject wrapped = new JsonObject();
            JsonArray servers = new JsonArray();
            servers.add(turnCredentials);
            wrapped.add("iceServers", servers);
            wrapped.addProperty("ttl", P2pConstants.turnCredentialTtlSeconds());
            return wrapped;
        }

        private static JsonObject parseApi3Object(String body, String message) throws IOException {
            try {
                return new JsonParser().parse(body).getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new IOException(message, exception);
            }
        }

        private static JsonArray toNetwork(InetSocketAddress endpoint) {
            JsonArray network = new JsonArray();
            InetAddress address = endpoint.getAddress();
            network.add(P2pSockets.addressFamily(endpoint));
            network.add(address == null ? endpoint.getHostString() : address.getHostAddress());
            network.add(endpoint.getPort());
            return network;
        }

        private static JsonArray array(JsonObject object, String key) {
            JsonElement element = object.get(key);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
        }

        private static JsonObject object(JsonObject object, String key) {
            JsonElement element = object.get(key);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        }

        private static InetSocketAddress relayNetwork(JsonObject relay) {
            if (relay == null) {
                return null;
            }
            JsonArray network = array(relay, "network");
            return network == null ? null : fromNetwork(network);
        }

        private static P2pTurnCredentials relayCredentials(JsonObject relay) throws IOException {
            if (relay == null || !relay.has("urls") || !relay.has("username") || !relay.has("credential")) {
                return null;
            }
            return P2pTurnCredentialClient.parse(wrapIceServers(relay));
        }

        private static InetSocketAddress fromNetwork(JsonArray network) {
            if (network == null || network.size() < 3) {
                return null;
            }
            String host = network.get(1).getAsString();
            int port = integer(network.get(2), 0);
            if (host.isBlank() || port < 1 || port > 65535) {
                return null;
            }
            try {
                return new InetSocketAddress(InetAddress.getByName(host), port);
            } catch (IOException exception) {
                LOGGER.debug("Could not resolve Safra endpoint {}:{}", host, port, exception);
                return null;
            }
        }

        private static URI httpUri(String path) {
            String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
            if (base.isBlank()) {
                throw new IllegalStateException("rendezvous URL is not configured");
            }
            URI baseUri = URI.create(base);
            String scheme = switch (baseUri.getScheme().toLowerCase(Locale.ROOT)) {
                case "http", "https" -> baseUri.getScheme().toLowerCase(Locale.ROOT);
                case "ws" -> "http";
                case "wss" -> "https";
                default -> throw new IllegalArgumentException("unsupported rendezvous URL scheme: " + baseUri.getScheme());
            };
            return URI.create(scheme + "://" + baseUri.getAuthority() + path);
        }

        private static void closeQuietly(InputStream inputStream) {
            if (inputStream == null) {
                return;
            }
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }

        private static void sleepQuietly(long delayMs) throws IOException {
            try {
                TimeUnit.MILLISECONDS.sleep(delayMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Safra bekleme kesildi", exception);
            }
        }
    }

    static final class SessionStatus {
        private final boolean active;
        private final boolean relayReady;
        private final JsonObject relay;

        SessionStatus(boolean active, boolean relayReady, JsonObject relay) {
            this.active = active;
            this.relayReady = relayReady;
            this.relay = relay;
        }

        boolean active() {
            return active;
        }

        boolean relayReady() {
            return relayReady;
        }

        JsonObject relay() {
            return relay;
        }

        String describeRelay() {
            return relay == null ? "-" : GSON.toJson(relay);
        }
    }

    private record ResolvedHost(InetSocketAddress address, InetSocketAddress relayAddress, int tunnelToken, int minecraftTcpPort) {
    }

    static final record ResolvedRelay(InetSocketAddress address, int tunnelToken, P2pTurnCredentials credentials) {
    }

    private record ResolvedVoiceHost(InetSocketAddress address) {
    }

    private record UdpEndpoint(String host, int port, String family) {
        static UdpEndpoint from(InetSocketAddress publicEndpoint) {
            InetAddress address = publicEndpoint.getAddress();
            String host = address == null ? publicEndpoint.getHostString() : address.getHostAddress();
            String family = address != null && address.getAddress().length == 16 ? "ipv6" : "ipv4";
            return new UdpEndpoint(host, publicEndpoint.getPort(), family);
        }

        static JsonArray toJsonArray(Collection<InetSocketAddress> publicEndpoints) {
            JsonArray array = new JsonArray();
            if (publicEndpoints == null) {
                return array;
            }

            for (InetSocketAddress endpoint : publicEndpoints) {
                if (endpoint == null || endpoint.getAddress() == null) {
                    continue;
                }
                array.add(from(endpoint).toJson());
            }
            return array;
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("host", host);
            object.addProperty("port", port);
            object.addProperty("family", family);
            return object;
        }
    }
}
