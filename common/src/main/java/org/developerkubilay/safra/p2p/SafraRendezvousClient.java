package org.developerkubilay.safra.p2p;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocketHandshakeException;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
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
                                 Consumer<InetSocketAddress> punchHandler,
                                 Consumer<InetSocketAddress> voicePunchHandler) throws IOException {
        SafraRendezvousClient client = new SafraRendezvousClient();
        HostListener listener = new HostListener(punchHandler, voicePunchHandler);
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
            return new HostSession(code, client);
        } catch (Exception exception) {
            client.close();
            throw asIOException("Safra rendezvous host setup failed", exception);
        }
    }

    static JoinSession join(String code, Collection<InetSocketAddress> publicEndpoints) throws IOException {
        SafraRendezvousClient client = new SafraRendezvousClient();
        JoinListener listener = new JoinListener();
        try {
            String peerId = "joiner-" + UUID.randomUUID();
            client.connect(webSocketUri("/v1/join/" + encode(code), peerId), listener);
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
            return new JoinSession(code, resolvedHost.address(), resolvedHost.tunnelToken(),
                resolvedHost.minecraftTcpPort(), client, listener);
        } catch (Exception exception) {
            client.close();
            throw asIOException("Safra rendezvous join setup failed", exception);
        }
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
            connect(webSocketUri("/v1/host", peerId, normalizedPreferredCode), listener);
        } catch (Exception exception) {
            if (normalizedPreferredCode != null && isActiveCodeConflict(exception)) {
                connect(webSocketUri("/v1/host", peerId, null), listener);
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

        private HostListener(Consumer<InetSocketAddress> punchHandler, Consumer<InetSocketAddress> voicePunchHandler) {
            this.punchHandler = punchHandler;
            this.voicePunchHandler = voicePunchHandler;
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
        private volatile CompletableFuture<ResolvedVoiceHost> resolvedVoiceFuture;

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
                int token = tunnelToken(message.getAsJsonObject("tunnel"));
                if (endpoint == null) {
                    fail(new IOException("rendezvous host endpoint is missing"));
                    return;
                }
                JsonObject metadata = message.getAsJsonObject("metadata");
                int minecraftTcpPort = minecraftTcpPort(metadata);
                resolvedHostFuture.complete(new ResolvedHost(endpoint, token, minecraftTcpPort));
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
            CompletableFuture<ResolvedVoiceHost> future = resolvedVoiceFuture;
            if (future != null) {
                future.completeExceptionally(throwable);
            }
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
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

    static final class HostSession implements AutoCloseable {
        private final String code;
        private final SafraRendezvousClient client;

        HostSession(String code, SafraRendezvousClient client) {
            this.code = code;
            this.client = client;
        }

        String code() {
            return code;
        }

        void publishVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            client.publishVoice(publicEndpoints);
        }

        @Override
        public void close() {
            client.close();
        }
    }

    static final class JoinSession implements AutoCloseable {
        private final String code;
        private final InetSocketAddress hostAddress;
        private final int tunnelToken;
        private final int hostTcpPort;
        private final SafraRendezvousClient client;
        private final JoinListener listener;

        JoinSession(String code, InetSocketAddress hostAddress, int tunnelToken, int hostTcpPort,
                    SafraRendezvousClient client, JoinListener listener) {
            this.code = code;
            this.hostAddress = hostAddress;
            this.tunnelToken = tunnelToken;
            this.hostTcpPort = hostTcpPort;
            this.client = client;
            this.listener = listener;
        }

        String code() {
            return code;
        }

        InetSocketAddress hostAddress() {
            return hostAddress;
        }

        int tunnelToken() {
            return tunnelToken;
        }

        int hostTcpPort() {
            return hostTcpPort;
        }

        InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            return client.resolveVoice(listener, publicEndpoints);
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private record ResolvedHost(InetSocketAddress address, int tunnelToken, int minecraftTcpPort) {
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
