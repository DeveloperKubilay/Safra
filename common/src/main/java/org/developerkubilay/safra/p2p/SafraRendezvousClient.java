package org.developerkubilay.safra.p2p;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class SafraRendezvousClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafraRendezvousClient.class);
    private static final Gson GSON = new Gson();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build();

    private WebSocket webSocket;
    private volatile boolean closed;

    static HostSession startHost(int tcpPort, int tunnelToken, String preferredRendezvousCode, Collection<InetSocketAddress> publicEndpoints,
                                 Consumer<InetSocketAddress> punchHandler,
                                 Consumer<InetSocketAddress> voicePunchHandler,
                                 Consumer<InetSocketAddress> relayRequestHandler) throws IOException {
        SafraRendezvousClient client = new SafraRendezvousClient();
        HostListener listener = new HostListener(punchHandler, voicePunchHandler, relayRequestHandler);
        try {
            String peerId = "host-" + UUID.randomUUID();
            client.connect(webSocketUri("/v3/host", peerId, preferredRendezvousCode), listener);
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
            return new JoinSession(code, resolvedHost.address(), resolvedHost.relayAddress(), resolvedHost.tunnelToken(),
                resolvedHost.minecraftTcpPort(), client, listener);
        } catch (Exception exception) {
            client.close();
            throw asIOException("Safra rendezvous join setup failed", exception);
        }
    }

    static SessionStatus fetchSessionStatus(String code) throws IOException {
        if (!P2pConstants.hasRendezvousUrl()) {
            throw new IOException("rendezvous URL is not configured");
        }

        Request.Builder builder = new Request.Builder()
            .url(httpUri("/v3/sessions/" + encode(code)).toString())
            .get();
        String token = P2pConstants.rendezvousToken();
        if (!token.trim().isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }

        Response response = httpClientForStatus().newCall(builder.build()).execute();
        try {
            if (response.code() == 404) {
                return new SessionStatus(false, false, null);
            }
            if (response.code() < 200 || response.code() >= 300) {
                throw new IOException("rendezvous status request failed with HTTP " + response.code());
            }

            String body = response.body() != null ? response.body().string() : "";
            JsonObject message;
            try {
                message = new JsonParser().parse(body).getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new IOException("rendezvous status response was invalid", exception);
            }

            JsonObject relay = object(message, "relay");
            return new SessionStatus(
                booleanValue(message, "active"),
                "ready".equals(string(message, "relayStatus")) && relay != null && endpoint(object(relay, "udp")) != null,
                relay
            );
        } finally {
            response.close();
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

    private void connect(URI uri, WebSocketListener listener) throws Exception {
        Request.Builder requestBuilder = new Request.Builder().url(uri.toString());
        String token = P2pConstants.rendezvousToken();
        if (!token.trim().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        webSocket = httpClient.newWebSocket(requestBuilder.build(), listener);
    }

    private static OkHttpClient httpClientForStatus() {
        return new OkHttpClient.Builder()
            .connectTimeout(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();
    }

    private void send(JsonObject message) {
        WebSocket socket = webSocket;
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
        WebSocket socket = webSocket;
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
        String scheme;
        String lowerScheme = baseUri.getScheme().toLowerCase(Locale.ROOT);
        switch (lowerScheme) {
            case "http":
                scheme = "ws";
                break;
            case "https":
                scheme = "wss";
                break;
            case "ws":
            case "wss":
                scheme = lowerScheme;
                break;
            default:
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
        String scheme;
        String lowerScheme = baseUri.getScheme().toLowerCase(Locale.ROOT);
        switch (lowerScheme) {
            case "http":
            case "https":
                scheme = lowerScheme;
                break;
            case "ws":
                scheme = "http";
                break;
            case "wss":
                scheme = "https";
                break;
            default:
                throw new IllegalArgumentException("unsupported rendezvous URL scheme: " + baseUri.getScheme());
        }

        return URI.create(scheme + "://" + baseUri.getAuthority() + path);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
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
        Throwable cause = exception;
        if (exception instanceof java.util.concurrent.ExecutionException) {
            java.util.concurrent.ExecutionException executionException = (java.util.concurrent.ExecutionException) exception;
            if (executionException.getCause() != null) {
                cause = executionException.getCause();
            }
        }
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

    private abstract static class JsonListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonObject message = new JsonParser().parse(text).getAsJsonObject();
                handle(message);
            } catch (RuntimeException exception) {
                fail(exception);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            fail(t);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            fail(new IOException("rendezvous websocket closed: " + code + " " + reason));
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
                    completeRelayFuture(new ResolvedRelay(relayAddress, token));
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
                completeRelayFuture(new ResolvedRelay(relayAddress, token));
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

    private static InetSocketAddress relayEndpoint(JsonObject relayObject) {
        return relayObject == null ? null : endpoint(object(relayObject, "udp"));
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

        void publishRelay(Collection<InetSocketAddress> publicEndpoints, String mode) throws IOException {
            InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
            if (primaryEndpoint == null) {
                throw new IOException("rendezvous relay host requires at least one UDP candidate");
            }

            JsonObject relay = new JsonObject();
            relay.addProperty("type", "host:relay-ready");
            relay.add("udp", UdpEndpoint.from(primaryEndpoint).toJson());
            relay.add("udpCandidates", UdpEndpoint.toJsonArray(publicEndpoints));
            relay.addProperty("mode", mode == null || mode.trim().isEmpty() ? "auto" : mode);
            client.send(relay);
        }

        void publishRelayFailure(String mode, String message) {
            JsonObject relay = new JsonObject();
            relay.addProperty("type", "host:relay-failed");
            relay.addProperty("mode", mode == null || mode.trim().isEmpty() ? "auto" : mode);
            if (message != null && !message.trim().isEmpty()) {
                relay.addProperty("message", message);
            }
            client.send(relay);
        }

        @Override
        public void close() {
            client.close();
        }
    }

    static final class JoinSession implements AutoCloseable {
        private final String code;
        private final InetSocketAddress hostAddress;
        private final InetSocketAddress relayAddress;
        private final int tunnelToken;
        private final int hostTcpPort;
        private final SafraRendezvousClient client;
        private final JoinListener listener;

        JoinSession(String code, InetSocketAddress hostAddress, InetSocketAddress relayAddress, int tunnelToken, int hostTcpPort,
                    SafraRendezvousClient client, JoinListener listener) {
            this.code = code;
            this.hostAddress = hostAddress;
            this.relayAddress = relayAddress;
            this.tunnelToken = tunnelToken;
            this.hostTcpPort = hostTcpPort;
            this.client = client;
            this.listener = listener;
        }

        String code() {
            return code;
        }

        InetSocketAddress hostAddress(boolean relayPreferred) {
            if (relayPreferred && relayAddress != null) {
                return relayAddress;
            }
            return relayPreferred ? null : hostAddress;
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

        ResolvedRelay requestRelayFallback(Collection<InetSocketAddress> relayEndpoints) throws IOException {
            if (relayAddress != null) {
                return new ResolvedRelay(relayAddress, tunnelToken);
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
            if (P2pConstants.forceDirectThenTurnRelay()) {
                LOGGER.info("Safra test modu join rendezvous relay istegi gonderdi code={} relayEndpoints={}",
                    code, relayEndpoints == null ? Collections.emptyList() : relayEndpoints);
            }
            try {
                return future.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                throw asIOException("Safra rendezvous relay fallback failed", exception);
            }
        }

        @Override
        public void close() {
            client.close();
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

    private static final class ResolvedHost {
        private final InetSocketAddress address;
        private final InetSocketAddress relayAddress;
        private final int tunnelToken;
        private final int minecraftTcpPort;

        ResolvedHost(InetSocketAddress address, InetSocketAddress relayAddress, int tunnelToken, int minecraftTcpPort) {
            this.address = address;
            this.relayAddress = relayAddress;
            this.tunnelToken = tunnelToken;
            this.minecraftTcpPort = minecraftTcpPort;
        }

        InetSocketAddress address() {
            return address;
        }

        InetSocketAddress relayAddress() {
            return relayAddress;
        }

        int tunnelToken() {
            return tunnelToken;
        }

        int minecraftTcpPort() {
            return minecraftTcpPort;
        }
    }

    static final class ResolvedRelay {
        private final InetSocketAddress address;
        private final int tunnelToken;

        ResolvedRelay(InetSocketAddress address, int tunnelToken) {
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
