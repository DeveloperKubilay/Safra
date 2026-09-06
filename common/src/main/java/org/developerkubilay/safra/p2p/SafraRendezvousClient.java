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
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** HTTP/SSE client for Safra session negotiation. */
final class SafraRendezvousClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafraRendezvousClient.class);
    private static final Gson GSON = new Gson();
    private static final int[] CONNECT_RETRY_DELAYS_MS = {0, 750};

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_REQUEST_TIMEOUT_MS))
        .build();

    private SafraRendezvousClient() {
    }

    static HostSession startHost(int tcpPort, int tunnelToken, String preferredCode,
                                 Collection<InetSocketAddress> publicEndpoints,
                                 Collection<InetSocketAddress> voicePublicEndpoints,
                                 Consumer<InetSocketAddress> punchHandler,
                                 Consumer<InetSocketAddress> voicePunchHandler,
                                 Consumer<InetSocketAddress> relayRequestHandler) throws IOException {
        InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
        HttpHostSessionBackend backend = new HttpHostSessionBackend(punchHandler, voicePunchHandler, relayRequestHandler);
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
        HttpJoinSessionBackend backend = new HttpJoinSessionBackend(code);
        backend.open(preferredEndpoint(publicEndpoints));
        return new JoinSession(code, backend);
    }

    static BedrockRelay requestBedrockRelay(String code) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("code", code);
        HttpResponse<String> response = sendText(requestBuilder(httpUri("/bedrock-request"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
            .build());

        if (response.statusCode() == 409) {
            LOGGER.warn("Safra Bedrock relay was already assigned for session {}", code);
            return null;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOGGER.warn("Safra Bedrock request returned HTTP {}", response.statusCode());
            return null;
        }

        JsonObject responseBody = parseJsonObject(response.body(), "Safra Bedrock request returned an invalid response");
        if (!responseBody.has("ok") || !responseBody.get("ok").getAsBoolean()) {
            return null;
        }
        try {
            String host = responseBody.get("bedrockServer").getAsString();
            int port = responseBody.get("bedrockPort").getAsInt();
            return host.isBlank() || port < 1 || port > 65535 ? null : new BedrockRelay(host, port);
        } catch (RuntimeException exception) {
            throw new IOException("Safra Bedrock request returned an invalid response", exception);
        }
    }

    private static final class HttpHostSessionBackend implements HostSessionBackend {
        private final Consumer<InetSocketAddress> punchHandler;
        private final Consumer<InetSocketAddress> voicePunchHandler;
        private final Consumer<InetSocketAddress> relayRequestHandler;
        private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
        private volatile InputStream stream;
        private volatile Thread streamThread;
        private volatile Thread relayRequestThread;
        private volatile boolean closed;
        private volatile JsonObject hostRequest;
        private volatile String code;
        private volatile InetSocketAddress lastJoinerAddress;
        private volatile P2pTurnCredentials pendingRelayCredentials;
        private volatile boolean relayAssigned;
        private volatile boolean relayRequestQueued;

        private HttpHostSessionBackend(Consumer<InetSocketAddress> punchHandler,
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

            hostRequest = request;
            openEventStream(request);
            streamThread = P2pRuntime.start("safra-rendezvous-host-events", this::readEvents);
            try {
                return codeFuture.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                throw new IOException("Safra host setup failed", exception);
            }
        }

        @Override
        public void publishRelay(Collection<InetSocketAddress> publicEndpoints, String mode) throws IOException {
            InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
            if (primaryEndpoint == null || code == null || code.isBlank()) {
                throw new IOException("Safra relay host requires an active session and UDP endpoint");
            }

            JsonObject request = new JsonObject();
            request.addProperty("code", code);
            request.add("network", toNetwork(primaryEndpoint));
            HttpResponse<String> response = sendText(requestBuilder(httpUri("/relay-accept"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                .build());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Safra relay publish returned HTTP " + response.statusCode());
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

        private void openEventStream(JsonObject request) throws IOException {
            HttpRequest httpRequest = requestBuilder(httpUri("/session-create"))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                .build();

            HttpResponse<InputStream> response = sendInputStream(httpRequest, "Safra host request was interrupted");

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Safra host request returned HTTP " + response.statusCode());
            }

            stream = response.body();
        }

        private void readEvents() {
            while (!closed) {
                try {
                    readCurrentEventStream();
                    if (!closed) {
                        throw new IOException("Safra host event stream closed");
                    }
                    return;
                } catch (IOException exception) {
                    if (closed) {
                        return;
                    }
                    LOGGER.debug("Safra host event stream closed: {}", exception.toString());
                }

                if (!reconnectEventStream()) {
                    return;
                }
            }
        }

        private boolean reconnectEventStream() {
            long reconnectStartedAt = System.currentTimeMillis();
            int attempt = 0;
            while (!closed) {
                long elapsedMs = System.currentTimeMillis() - reconnectStartedAt;
                long delayMs = attempt == 0
                    ? P2pConstants.RENDEZVOUS_RECONNECT_FIRST_DELAY_MS
                    : elapsedMs >= P2pConstants.RENDEZVOUS_RECONNECT_SLOW_AFTER_MS
                        ? P2pConstants.RENDEZVOUS_RECONNECT_SLOW_DELAY_MS
                        : P2pConstants.RENDEZVOUS_RECONNECT_DELAY_MS;
                attempt++;
                try {
                    sleepQuietly(delayMs);
                    openEventStream(reconnectRequest());
                    LOGGER.info("Safra host event stream reconnected attempt={}", attempt);
                    return true;
                } catch (IOException exception) {
                    if (!closed) {
                        LOGGER.debug("Safra host event stream reconnect attempt {} failed: {}", attempt, exception.toString());
                    }
                }
            }
            return false;
        }

        private void readCurrentEventStream() throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String event = "";
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!event.isBlank()) {
                            handleEvent(event, parseJsonObject(data.toString(), "Safra event payload is invalid"));
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
        }

        private JsonObject reconnectRequest() {
            JsonObject request = hostRequest == null ? new JsonObject() : hostRequest.deepCopy();
            if (code != null && !code.isBlank()) {
                request.addProperty("code", code);
            }
            return request;
        }

        private void handleEvent(String event, JsonObject data) throws IOException {
            if ("session-created".equals(event)) {
                String receivedCode = string(data, "code");
                if (code != null && !code.isBlank() && receivedCode != null && !code.equals(receivedCode)) {
                    throw new IOException("Safra host reconnect returned a different code");
                }
                code = receivedCode;
                codeFuture.complete(code);
                if (relayAssigned) {
                    relayRequestHandler.accept(lastJoinerAddress);
                }
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
                HttpResponse<InputStream> response = sendInputStream(httpRequest, "Safra host relay request was interrupted");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Safra host relay request returned HTTP " + response.statusCode());
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    while (!closed && reader.readLine() != null) {
                    }
                }
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.warn("Safra host relay request failed: {}", exception.toString());
                }
            }
        }
    }

    private static final class HttpJoinSessionBackend implements JoinSessionBackend {
        private final String code;
        private InetSocketAddress joinAddress;
        private InetSocketAddress hostAddress;
        private InetSocketAddress voiceAddress;
        private InetSocketAddress relayAddress;
        private P2pTurnCredentials relayCredentials;

        private HttpJoinSessionBackend(String code) {
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
                throw new IOException("Safra join request returned HTTP " + response.statusCode());
            }

            JsonObject json = parseJsonObject(response.body(), "Safra join response is invalid");
            hostAddress = fromNetwork(array(json, "host"));
            voiceAddress = fromNetwork(array(json, "voiceHost"));
            JsonObject relay = object(json, "relay");
            relayAddress = relayNetwork(relay);
            relayCredentials = relayCredentials(relay);
            if (hostAddress == null && relayAddress == null) {
                throw new IOException("Safra join response did not include a host address");
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

            throw new IOException("Safra voice endpoint was not received in time");
        }

        @Override
        public InetSocketAddress refreshDirect(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            InetSocketAddress endpoint = preferredEndpoint(publicEndpoints);
            if (endpoint != null) {
                joinAddress = endpoint;
            }
            if (joinAddress == null) {
                throw new IOException("Safra direct retry requires a STUN endpoint");
            }
            refreshHostState(joinAddress);
            return hostAddress;
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

            HttpResponse<InputStream> response = sendInputStream(httpRequest, "Safra relay request was interrupted");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Safra relay request returned HTTP " + response.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String event = "";
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!event.isBlank()) {
                            JsonObject json = parseJsonObject(data.toString(), "Safra relay payload is invalid");
                            if ("relay-accepted".equals(event)) {
                                JsonObject relay = object(json, "relay");
                                relayAddress = relay != null ? relayNetwork(relay) : fromNetwork(array(json, "network"));
                                relayCredentials = relay != null ? relayCredentials(relay) : relayCredentials;
                                if (relayAddress == null) {
                                    throw new IOException("Safra relay response did not include a network endpoint");
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

            throw new IOException("Safra relay event stream closed");
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
            request.add("network", toNetwork(endpoint));
            HttpResponse<String> response = sendText(requestBuilder(httpUri("/session-join"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                .build());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return;
            }

            JsonObject json = parseJsonObject(response.body(), "Safra join refresh response is invalid");
            InetSocketAddress refreshedHost = fromNetwork(array(json, "host"));
            if (refreshedHost != null) {
                hostAddress = refreshedHost;
            }
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
                throw new IOException("Safra voice update request returned HTTP " + response.statusCode());
            }
        }
    }

    private static HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri)
            .header("User-Agent", SafraBuildInfo.userAgent())
            .timeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_REQUEST_TIMEOUT_MS));
    }

    private static HttpResponse<String> sendText(HttpRequest request) throws IOException {
        return sendWithConnectRetry(request, HttpResponse.BodyHandlers.ofString(), "Safra request was interrupted");
    }

    private static HttpResponse<InputStream> sendInputStream(HttpRequest request, String interruptedMessage) throws IOException {
        return sendWithConnectRetry(request, HttpResponse.BodyHandlers.ofInputStream(), interruptedMessage);
    }

    private static <T> HttpResponse<T> sendWithConnectRetry(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler,
                                                           String interruptedMessage) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < CONNECT_RETRY_DELAYS_MS.length; attempt++) {
            int delayMs = CONNECT_RETRY_DELAYS_MS[attempt];
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interruptedMessage, exception);
                }
            }

            try {
                return HTTP_CLIENT.send(request, bodyHandler);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(interruptedMessage, exception);
            } catch (IOException exception) {
                lastFailure = exception;
                if (!isRetryableApiConnectFailure(exception) || attempt + 1 >= CONNECT_RETRY_DELAYS_MS.length) {
                    throw exception;
                }
                LOGGER.warn("Safra API connection retry {}/{} for {} after {}", attempt + 1,
                    CONNECT_RETRY_DELAYS_MS.length - 1, request.uri().getHost(), exception.toString());
            }
        }

        throw lastFailure == null ? new IOException("Safra API connection failed") : lastFailure;
    }

    private static boolean isRetryableApiConnectFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException
                || current instanceof UnknownHostException
                || current instanceof UnresolvedAddressException
                || current instanceof HttpConnectTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static JsonObject wrapIceServers(JsonObject turnCredentials) {
        JsonObject wrapped = new JsonObject();
        JsonArray servers = new JsonArray();
        servers.add(turnCredentials);
        wrapped.add("iceServers", servers);
        wrapped.addProperty("ttl", P2pConstants.turnCredentialTtlSeconds());
        return wrapped;
    }

    private static JsonObject parseJsonObject(String body, String message) throws IOException {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
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
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean booleanValue(JsonObject object, String key) {
        if (object == null) {
            return false;
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
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
        int port = network.get(2).getAsInt();
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

    private static InetSocketAddress preferredEndpoint(Collection<InetSocketAddress> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return null;
        }
        for (InetSocketAddress endpoint : endpoints) {
            if (endpoint != null && endpoint.getAddress() != null) {
                return endpoint;
            }
        }
        return endpoints.iterator().next();
    }

    private static URI httpUri(String path) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
        if (base.isBlank()) {
            throw new IllegalStateException("rendezvous URL is not configured");
        }
        URI baseUri = URI.create(base);
        String scheme = switch (baseUri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http", "https" -> baseUri.getScheme().toLowerCase(Locale.ROOT);
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
            throw new IOException("Safra wait was interrupted", exception);
        }
    }

    interface HostSessionBackend {
        void publishRelay(Collection<InetSocketAddress> publicEndpoints, String mode) throws IOException;
        void publishRelayFailure(String mode, String message);
        P2pTurnCredentials consumePendingRelayCredentials();
        void close();
    }

    interface JoinSessionBackend {
        InetSocketAddress hostAddress(boolean relayPreferred);
        InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException;
        InetSocketAddress refreshDirect(Collection<InetSocketAddress> publicEndpoints) throws IOException;
        ResolvedRelay requestRelayFallback(Collection<InetSocketAddress> relayEndpoints) throws IOException;
        void close();
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

        InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            return backend.resolveVoice(publicEndpoints);
        }

        InetSocketAddress refreshDirect(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            return backend.refreshDirect(publicEndpoints);
        }

        ResolvedRelay requestRelayFallback(Collection<InetSocketAddress> relayEndpoints) throws IOException {
            return backend.requestRelayFallback(relayEndpoints);
        }

        @Override
        public void close() {
            backend.close();
        }
    }

    static final record ResolvedRelay(InetSocketAddress address, int tunnelToken, P2pTurnCredentials credentials) {
    }

    static final record BedrockRelay(String host, int port) {
    }
}
