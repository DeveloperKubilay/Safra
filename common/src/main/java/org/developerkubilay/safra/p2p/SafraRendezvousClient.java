package org.developerkubilay.safra.p2p;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class SafraRendezvousClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafraRendezvousClient.class);
    private static final Gson GSON = new Gson();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_TIMEOUT_MS))
        .build();

    private WebSocket webSocket;
    private ScheduledFuture<?> pingTask;
    private volatile boolean closed;

    static HostSession startHost(int tcpPort, int tunnelToken, Collection<InetSocketAddress> publicEndpoints,
                                 DatagramSocket socket, Consumer<InetSocketAddress> punchHandler) throws IOException {
        SafraRendezvousClient client = new SafraRendezvousClient();
        HostListener listener = new HostListener(punchHandler);
        try {
            String peerId = "host-" + UUID.randomUUID();
            client.connect(webSocketUri("/v1/host", peerId), listener);
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
            ready.add("metadata", metadata);

            client.send(ready);
            listener.readyFuture.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            client.startPing();
            return new HostSession(code, client);
        } catch (Exception exception) {
            client.close();
            throw asIOException("Safra rendezvous host setup failed", exception);
        }
    }

    static JoinSession join(String code, Collection<InetSocketAddress> publicEndpoints, DatagramSocket socket) throws IOException {
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
            client.send(ready);

            ResolvedHost resolvedHost = listener.resolvedHostFuture.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            client.startPing();
            return new JoinSession(resolvedHost.address(), resolvedHost.tunnelToken(), client);
        } catch (Exception exception) {
            client.close();
            throw asIOException("Safra rendezvous join setup failed", exception);
        }
    }

    private void connect(URI uri, WebSocket.Listener listener) throws Exception {
        WebSocket.Builder builder = httpClient.newWebSocketBuilder();
        String token = P2pConstants.rendezvousToken();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        webSocket = builder
            .buildAsync(uri, listener)
            .get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void deleteSession(String code) {
        if (code == null || code.isBlank()) {
            return;
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(httpUri("/v1/sessions/" + encode(code)))
                .timeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_TIMEOUT_MS))
                .DELETE();
            String token = P2pConstants.rendezvousToken();
            if (!token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }

            httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.debug("Safra rendezvous session delete request failed: {}", throwable.toString());
                    } else if (response.statusCode() >= 400) {
                        LOGGER.debug("Safra rendezvous session delete request returned HTTP {}", response.statusCode());
                    }
                });
        } catch (RuntimeException exception) {
            LOGGER.debug("Safra rendezvous session delete request could not be sent: {}", exception.toString());
        }
    }

    private void send(JsonObject message) {
        WebSocket socket = webSocket;
        if (socket != null && !closed) {
            socket.sendText(GSON.toJson(message), true);
        }
    }

    private void startPing() {
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            JsonObject ping = new JsonObject();
            ping.addProperty("type", "ping");
            send(ping);
        }, P2pConstants.RENDEZVOUS_PING_MS, P2pConstants.RENDEZVOUS_PING_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScheduledFuture<?> task = pingTask;
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdownNow();
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "safra closed");
        }
    }

    private static URI webSocketUri(String path, String peerId) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
        URI baseUri = URI.create(base);
        String scheme = switch (baseUri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http" -> "ws";
            case "https" -> "wss";
            case "ws", "wss" -> baseUri.getScheme().toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("unsupported rendezvous URL scheme: " + baseUri.getScheme());
        };

        return URI.create(scheme + "://" + baseUri.getAuthority() + path + "?peerId=" + encode(peerId));
    }

    private static URI httpUri(String path) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
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
        return new IOException(message + ": " + cause.getMessage(), cause);
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
                    JsonObject message = JsonParser.parseString(buffer.toString()).getAsJsonObject();
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

        private HostListener(Consumer<InetSocketAddress> punchHandler) {
            this.punchHandler = punchHandler;
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

    record HostSession(String code, SafraRendezvousClient client) implements AutoCloseable {
        @Override
        public void close() {
            client.deleteSession(code);
            client.close();
        }
    }

    record JoinSession(InetSocketAddress hostAddress, int tunnelToken, SafraRendezvousClient client) implements AutoCloseable {
        @Override
        public void close() {
            client.close();
        }
    }

    private record ResolvedHost(InetSocketAddress address, int tunnelToken) {
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
