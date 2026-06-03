package org.developerkubilay.safra.p2p;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;
import org.developerkubilay.safra.util.SafraLogger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class SafraRendezvousClient implements AutoCloseable {
    private static final Logger LOGGER = SafraLogger.get(SafraRendezvousClient.class);
    private static final Gson GSON = new Gson();

    private WebSocketClient webSocket;
    private volatile boolean closed;

    static HostSession startHost(int tcpPort, int tunnelToken, String preferredRendezvousCode, Collection<InetSocketAddress> publicEndpoints,
                                 Consumer<InetSocketAddress> punchHandler,
                                 Consumer<InetSocketAddress> voicePunchHandler) throws IOException {
        SafraRendezvousClient client = new SafraRendezvousClient();
        HostListener listener = new HostListener(punchHandler, voicePunchHandler);
        try {
            String peerId = "host-" + UUID.randomUUID();
            client.connect(webSocketUri("/v1/host", peerId, preferredRendezvousCode), listener);
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
            return new JoinSession(code, resolvedHost.address(), resolvedHost.tunnelToken(), client, listener);
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

    private void connect(URI uri, JsonListener listener) throws Exception {
        Map<String, String> headers = new HashMap<>();
        String token = P2pConstants.rendezvousToken();
        if (!token.trim().isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }

        CountDownLatch connectLatch = new CountDownLatch(1);
        AtomicReference<Exception> connectError = new AtomicReference<>();

        webSocket = new WebSocketClient(uri, new org.java_websocket.drafts.Draft_6455(), headers, (int)P2pConstants.RENDEZVOUS_TIMEOUT_MS) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                listener.onOpen();
                connectLatch.countDown();
            }

            @Override
            public void onMessage(String message) {
                listener.onText(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                listener.onClose(code, reason);
            }

            @Override
            public void onError(Exception ex) {
                if (connectLatch.getCount() > 0) {
                    connectError.set(ex);
                    connectLatch.countDown();
                } else {
                    listener.onError(ex);
                }
            }
        };

        webSocket.connect();
        if (!connectLatch.await(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            webSocket.close();
            throw new IOException("WebSocket connection timeout");
        }
        if (connectError.get() != null) {
            throw new IOException("WebSocket connection failed", connectError.get());
        }
    }

    private void send(JsonObject message) {
        WebSocketClient socket = webSocket;
        if (socket != null && !closed) {
            socket.send(GSON.toJson(message));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        WebSocketClient socket = webSocket;
        if (socket != null) {
            socket.close(1000, "safra closed");
        }
    }

    private static URI webSocketUri(String path, String peerId) {
        return webSocketUri(path, peerId, null);
    }

    private static URI webSocketUri(String path, String peerId, String fixedCode) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
        if (base.trim().isEmpty()) {
            throw new IllegalStateException("rendezvous URL is not configured");
        }
        URI baseUri = URI.create(base);
        String schemeRaw = baseUri.getScheme().toLowerCase(Locale.ROOT);
        String scheme;
        if ("http".equals(schemeRaw)) {
            scheme = "ws";
        } else if ("https".equals(schemeRaw)) {
            scheme = "wss";
        } else if ("ws".equals(schemeRaw) || "wss".equals(schemeRaw)) {
            scheme = schemeRaw;
        } else {
            throw new IllegalArgumentException("unsupported rendezvous URL scheme: " + baseUri.getScheme());
        }

        StringBuilder uri = new StringBuilder()
            .append(scheme)
            .append("://")
            .append(baseUri.getAuthority())
            .append(path)
            .append("?peerId=")
            .append(encode(peerId));
        if (fixedCode != null && !fixedCode.trim().isEmpty()) {
            uri.append("&fixedCode=").append(encode(fixedCode));
        }
        return URI.create(uri.toString());
    }

    private static URI httpUri(String path) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
        if (base.trim().isEmpty()) {
            throw new IllegalStateException("rendezvous URL is not configured");
        }
        URI baseUri = URI.create(base);
        String schemeRaw2 = baseUri.getScheme().toLowerCase(Locale.ROOT);
        String scheme;
        if ("http".equals(schemeRaw2) || "https".equals(schemeRaw2)) {
            scheme = schemeRaw2;
        } else if ("ws".equals(schemeRaw2)) {
            scheme = "http";
        } else if ("wss".equals(schemeRaw2)) {
            scheme = "https";
        } else {
            throw new IllegalArgumentException("unsupported rendezvous URL scheme: " + baseUri.getScheme());
        }

        return URI.create(scheme + "://" + baseUri.getAuthority() + path);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new RuntimeException("UTF-8 not supported", exception);
        }
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
        Throwable cause = exception instanceof java.util.concurrent.ExecutionException
            ? ((java.util.concurrent.ExecutionException) exception).getCause()
            : exception;
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        if (cause instanceof TimeoutException) {
            return new IOException(message + ": timeout", cause);
        }
        return new IOException(message + ": " + cause.getMessage(), cause);
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

    private abstract static class JsonListener {

        public void onOpen() {
        }

        public void onText(String data) {
            try {
                JsonObject message = new JsonParser().parse(data).getAsJsonObject();
                handle(message);
            } catch (RuntimeException exception) {
                fail(exception);
            }
        }

        public void onError(Exception error) {
            fail(error);
        }

        public void onClose(int statusCode, String reason) {
            fail(new IOException("rendezvous websocket closed: " + statusCode + " " + reason));
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
                resolvedHostFuture.complete(new ResolvedHost(endpoint, token));
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
        if (host.trim().isEmpty() || port < 1 || port > 65535) {
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
        private final SafraRendezvousClient client;
        private final JoinListener listener;

        JoinSession(String code, InetSocketAddress hostAddress, int tunnelToken, SafraRendezvousClient client, JoinListener listener) {
            this.code = code;
            this.hostAddress = hostAddress;
            this.tunnelToken = tunnelToken;
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

        InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            return client.resolveVoice(listener, publicEndpoints);
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private static final class ResolvedHost {
        private final InetSocketAddress address;
        private final int tunnelToken;

        ResolvedHost(InetSocketAddress address, int tunnelToken) {
            this.address = address;
            this.tunnelToken = tunnelToken;
        }

        InetSocketAddress address() {
            return address;
        }

        int tunnelToken() {
            return tunnelToken;
        }
    }

    private static final class ResolvedVoiceHost {
        private final InetSocketAddress address;

        ResolvedVoiceHost(InetSocketAddress address) {
            this.address = address;
        }

        InetSocketAddress address() {
            return address;
        }
    }

    private static final class UdpEndpoint {
        private final String host;
        private final int port;
        private final String family;

        UdpEndpoint(String host, int port, String family) {
            this.host = host;
            this.port = port;
            this.family = family;
        }

        String host() {
            return host;
        }

        int port() {
            return port;
        }

        String family() {
            return family;
        }

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
