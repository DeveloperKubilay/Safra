package org.developerkubilay.safra.p2p;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.logging.log4j.Logger;
import org.developerkubilay.safra.util.SafraLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class SafraRendezvousClient {
    private static final Logger LOGGER = SafraLogger.get(SafraRendezvousClient.class);
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0L, TimeUnit.MILLISECONDS)
        .build();

    private SafraRendezvousClient() {
    }

    static HostSession startHost(int tcpPort, int tunnelToken, String preferredRendezvousCode, Collection<InetSocketAddress> publicEndpoints,
                                 Collection<InetSocketAddress> voicePublicEndpoints,
                                 Consumer<InetSocketAddress> punchHandler,
                                 Consumer<InetSocketAddress> voicePunchHandler,
                                 Consumer<InetSocketAddress> relayRequestHandler) throws IOException {
        InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
        if (primaryEndpoint == null) {
            throw new IOException("Safra host requires at least one UDP endpoint");
        }

        Api3HostSessionBackend backend = new Api3HostSessionBackend(punchHandler, voicePunchHandler, relayRequestHandler);
        try {
            String code = backend.open(
                tcpPort,
                tunnelToken,
                preferredRendezvousCode,
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
            throw new IOException("Safra join requires at least one UDP endpoint");
        }

        Api3JoinSessionBackend backend = new Api3JoinSessionBackend(code);
        backend.open(primaryEndpoint);
        return new JoinSession(code, backend);
    }

    static SessionStatus fetchSessionStatus(String code) throws IOException {
        if (!P2pConstants.hasRendezvousUrl()) {
            throw new IOException("rendezvous URL is not configured");
        }

        Response response = send(requestBuilder(httpUri("/v3/sessions/" + encode(code))).get().build());
        try {
            if (response.code() == 404) {
                return new SessionStatus(false, false, null);
            }
            if (response.code() < 200 || response.code() >= 300) {
                throw new IOException("rendezvous status request failed with HTTP " + response.code());
            }

            String body = response.body() != null ? response.body().string() : "";
            JsonObject message = parseApi3Object(body, "rendezvous status response was invalid");
            JsonObject relay = object(message, "relay");
            return new SessionStatus(
                booleanValue(message, "active"),
                "ready".equals(string(message, "relayStatus")) && relayNetwork(relay) != null,
                relay
            );
        } finally {
            response.close();
        }
    }

    private interface HostSessionBackend extends AutoCloseable {
        void publishVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException;

        void publishRelay(Collection<InetSocketAddress> publicEndpoints, String mode) throws IOException;

        void publishRelayFailure(String mode, String message);

        @Override
        void close();
    }

    private interface JoinSessionBackend extends AutoCloseable {
        InetSocketAddress hostAddress(boolean relayPreferred);

        int tunnelToken();

        int hostTcpPort();

        InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException;

        ResolvedRelay requestRelayFallback(Collection<InetSocketAddress> relayEndpoints) throws IOException;

        @Override
        void close();
    }

    private static final class Api3HostSessionBackend implements HostSessionBackend {
        private final Consumer<InetSocketAddress> punchHandler;
        private final Consumer<InetSocketAddress> voicePunchHandler;
        private final Consumer<InetSocketAddress> relayRequestHandler;
        private final CompletableFuture<String> codeFuture = new CompletableFuture<>();

        private volatile Response streamResponse;
        private volatile Thread streamThread;
        private volatile Thread relayRequestThread;
        private volatile boolean closed;
        private volatile JsonObject hostRequest;
        private volatile String code;
        private volatile InetSocketAddress lastJoinerAddress;
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
            request.add("network", toNetwork(endpoint));
            if (voiceEndpoint != null) {
                request.add("voicechat", toNetwork(voiceEndpoint));
            }
            request.addProperty("tunnelToken", tunnelToken);
            request.addProperty("minecraftTcpPort", tcpPort);
            if (preferredCode != null && !preferredCode.trim().isEmpty()) {
                request.addProperty("code", preferredCode);
            }

            hostRequest = request;
            openEventStream(request);
            streamThread = P2pRuntime.start("safra-rendezvous-host-events", this::readEvents);
            try {
                return codeFuture.get(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                throw asIOException("Safra host setup failed", exception);
            }
        }

        @Override
        public void publishVoice(Collection<InetSocketAddress> publicEndpoints) {
        }

        @Override
        public void publishRelay(Collection<InetSocketAddress> publicEndpoints, String mode) throws IOException {
            InetSocketAddress primaryEndpoint = preferredEndpoint(publicEndpoints);
            if (primaryEndpoint == null || code == null || code.trim().isEmpty()) {
                throw new IOException("Safra relay host requires an active session and a UDP endpoint");
            }

            JsonObject request = new JsonObject();
            request.addProperty("code", code);
            request.add("network", toNetwork(primaryEndpoint));
            Response response = send(requestBuilder(httpUri("/relay-accept"))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, GSON.toJson(request)))
                .build());
            try {
                if (response.code() < 200 || response.code() >= 300) {
                    throw new IOException("Safra relay publish returned HTTP " + response.code());
                }
            } finally {
                response.close();
            }
        }

        @Override
        public void publishRelayFailure(String mode, String message) {
            LOGGER.warn("Safra relay publish failed mode={} message={}",
                mode == null || mode.trim().isEmpty() ? "auto" : mode,
                message == null ? "" : message);
        }

        @Override
        public void close() {
            closed = true;
            closeQuietly(streamResponse);
            if (streamThread != null) {
                streamThread.interrupt();
            }
            if (relayRequestThread != null) {
                relayRequestThread.interrupt();
            }
        }

        private void openEventStream(JsonObject request) throws IOException {
            Response response = send(requestBuilder(httpUri("/session-create"))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, GSON.toJson(request)))
                .build());
            if (response.code() < 200 || response.code() >= 300) {
                response.close();
                throw new IOException("Safra host request returned HTTP " + response.code());
            }

            streamResponse = response;
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
            long deadline = System.currentTimeMillis() + P2pConstants.RENDEZVOUS_RECONNECT_WINDOW_MS;
            int attempt = 0;
            while (!closed && System.currentTimeMillis() < deadline) {
                attempt++;
                sleepQuietly(P2pConstants.RENDEZVOUS_RECONNECT_DELAY_MS);
                try {
                    openEventStream(reconnectRequest());
                    LOGGER.debug("Safra host event stream reconnected attempt={}", attempt);
                    return true;
                } catch (IOException exception) {
                    if (!closed) {
                        LOGGER.debug("Safra host event stream reconnect attempt {} failed: {}",
                            attempt,
                            exception.toString());
                    }
                }
            }

            if (!closed) {
                IOException exception = new IOException("Safra host event stream could not reconnect");
                codeFuture.completeExceptionally(exception);
                LOGGER.warn("Safra host event stream could not reconnect within 120 seconds");
            }
            return false;
        }

        private void readCurrentEventStream() throws IOException {
            Response response = streamResponse;
            if (response == null || response.body() == null) {
                throw new IOException("Safra host event stream yok");
            }

            try (Reader readerStream = response.body().charStream();
                 BufferedReader reader = new BufferedReader(readerStream)) {
                String event = "";
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!event.trim().isEmpty()) {
                            handleEvent(event, parseApi3Object(data.toString(), "Safra event payload is invalid"));
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
            } finally {
                closeQuietly(response);
            }
        }

        private JsonObject reconnectRequest() {
            JsonObject request = hostRequest == null ? new JsonObject() : GSON.fromJson(GSON.toJson(hostRequest), JsonObject.class);
            if (code != null && !code.trim().isEmpty()) {
                request.addProperty("code", code);
            }
            return request;
        }

        private void handleEvent(String event, JsonObject data) throws IOException {
            if ("session-created".equals(event)) {
                String receivedCode = string(data, "code");
                if (code != null && !code.trim().isEmpty() && receivedCode != null && !code.equals(receivedCode)) {
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
                relayAssigned = true;
                if (lastJoinerAddress != null) {
                    relayRequestHandler.accept(lastJoinerAddress);
                }
            }
        }

        private synchronized void queueRelayRequest() {
            if (relayRequestQueued || code == null || code.trim().isEmpty() || closed) {
                return;
            }
            relayRequestQueued = true;
            relayRequestThread = P2pRuntime.start("safra-rendezvous-host-relay-request", this::requestRelayAssignment);
        }

        private void requestRelayAssignment() {
            JsonObject request = new JsonObject();
            request.addProperty("code", code);
            Response response = null;
            try {
                response = send(requestBuilder(httpUri("/relay-request"))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, GSON.toJson(request)))
                    .build());
                if (response.code() < 200 || response.code() >= 300) {
                    throw new IOException("Safra host relay request returned HTTP " + response.code());
                }
                if (response.body() == null) {
                    return;
                }
                try (Reader readerStream = response.body().charStream();
                     BufferedReader reader = new BufferedReader(readerStream)) {
                    while (!closed && reader.readLine() != null) {
                    }
                }
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.warn("Safra host relay request failed: {}", exception.toString());
                }
            } finally {
                closeQuietly(response);
            }
        }
    }

    private static final class Api3JoinSessionBackend implements JoinSessionBackend {
        private final String code;
        private InetSocketAddress joinAddress;
        private InetSocketAddress hostAddress;
        private InetSocketAddress voiceAddress;
        private InetSocketAddress relayAddress;

        private Api3JoinSessionBackend(String code) {
            this.code = code;
        }

        private void open(InetSocketAddress endpoint) throws IOException {
            joinAddress = endpoint;
            JsonObject request = new JsonObject();
            request.addProperty("code", code);
            request.add("network", toNetwork(endpoint));
            Response response = send(requestBuilder(httpUri("/session-join"))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, GSON.toJson(request)))
                .build());
            try {
                if (response.code() < 200 || response.code() >= 300) {
                    throw new IOException("Safra join request returned HTTP " + response.code());
                }

                String body = response.body() != null ? response.body().string() : "";
                JsonObject json = parseApi3Object(body, "Safra join response is invalid");
                hostAddress = fromNetwork(array(json, "host"));
                voiceAddress = fromNetwork(array(json, "voiceHost"));
                relayAddress = relayNetwork(object(json, "relay"));
                if (hostAddress == null && relayAddress == null) {
                    throw new IOException("Safra join response did not include a host address");
                }
            } finally {
                response.close();
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

            throw new IOException("Safra voice endpoint did not arrive in time");
        }

        @Override
        public ResolvedRelay requestRelayFallback(Collection<InetSocketAddress> relayEndpoints) throws IOException {
            if (relayAddress != null) {
                InetSocketAddress localRelayEndpoint = preferredEndpoint(relayEndpoints);
                if (localRelayEndpoint != null) {
                    refreshHostState(localRelayEndpoint);
                }
                return new ResolvedRelay(relayAddress, P2pShareCode.rendezvousTunnelToken(code));
            }

            JsonObject request = new JsonObject();
            request.addProperty("code", code);
            Response response = send(requestBuilder(httpUri("/relay-request"))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, GSON.toJson(request)))
                .build());
            try {
                if (response.code() < 200 || response.code() >= 300) {
                    throw new IOException("Safra relay request returned HTTP " + response.code());
                }
                if (response.body() == null) {
                    throw new IOException("Safra relay event stream closed");
                }

                try (Reader readerStream = response.body().charStream();
                     BufferedReader reader = new BufferedReader(readerStream)) {
                    String event = "";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!event.trim().isEmpty()) {
                                JsonObject json = parseApi3Object(data.toString(), "Safra relay payload is invalid");
                                if ("relay-accepted".equals(event)) {
                                    JsonObject relay = object(json, "relay");
                                    relayAddress = relay != null ? relayNetwork(relay) : fromNetwork(array(json, "network"));
                                    if (relayAddress == null) {
                                        throw new IOException("Safra relay response did not include a network endpoint");
                                    }
                                    return new ResolvedRelay(relayAddress, P2pShareCode.rendezvousTunnelToken(code));
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
            } finally {
                response.close();
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
            Response response = send(requestBuilder(httpUri("/session-join"))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, GSON.toJson(request)))
                .build());
            try {
                if (response.code() < 200 || response.code() >= 300) {
                    return;
                }

                String body = response.body() != null ? response.body().string() : "";
                JsonObject json = parseApi3Object(body, "Safra join refresh response is invalid");
                InetSocketAddress refreshedHost = fromNetwork(array(json, "host"));
                if (refreshedHost != null) {
                    hostAddress = refreshedHost;
                }
                InetSocketAddress refreshedVoice = fromNetwork(array(json, "voiceHost"));
                if (refreshedVoice != null) {
                    voiceAddress = refreshedVoice;
                }
                InetSocketAddress refreshedRelay = relayNetwork(object(json, "relay"));
                if (refreshedRelay != null) {
                    relayAddress = refreshedRelay;
                }
            } finally {
                response.close();
            }
        }

        private void publishVoiceUpdate(InetSocketAddress endpoint) throws IOException {
            JsonObject request = new JsonObject();
            request.addProperty("code", code);
            request.add("voicechat", toNetwork(endpoint));
            Response response = send(requestBuilder(httpUri("/voicechat-update"))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, GSON.toJson(request)))
                .build());
            try {
                if (response.code() < 200 || response.code() >= 300) {
                    throw new IOException("Safra voice update request returned HTTP " + response.code());
                }
            } finally {
                response.close();
            }
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

    private static Request.Builder requestBuilder(URI uri) {
        Request.Builder builder = new Request.Builder().url(uri.toString());
        String token = P2pConstants.rendezvousToken();
        if (token != null && !token.trim().isEmpty()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        return builder;
    }

    private static Response send(Request request) throws IOException {
        return HTTP_CLIENT.newCall(request).execute();
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
        network.add(new JsonPrimitive(P2pSockets.addressFamily(endpoint)));
        network.add(new JsonPrimitive(address == null ? endpoint.getHostString() : address.getHostAddress()));
        network.add(new JsonPrimitive(endpoint.getPort()));
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

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static boolean booleanValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }

    private static InetSocketAddress relayNetwork(JsonObject relay) {
        if (relay == null) {
            return null;
        }
        JsonArray network = array(relay, "network");
        return network == null ? null : fromNetwork(network);
    }

    private static InetSocketAddress fromNetwork(JsonArray network) {
        if (network == null || network.size() < 3) {
            return null;
        }
        String host = network.get(1).getAsString();
        int port = integer(network.get(2), 0);
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            return null;
        }
        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (IOException exception) {
            LOGGER.debug("Could not resolve Safra endpoint {}:{}", host, port, exception);
            return null;
        }
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
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new RuntimeException(exception);
        }
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
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return new IOException(message + ": timeout", cause);
        }
        return new IOException(message + ": " + cause.getMessage(), cause);
    }

    private static void closeQuietly(Response response) {
        if (response != null) {
            response.close();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
