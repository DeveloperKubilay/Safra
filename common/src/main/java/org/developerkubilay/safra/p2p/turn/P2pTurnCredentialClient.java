package org.developerkubilay.safra.p2p.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.SafraBuildInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class P2pTurnCredentialClient {
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(P2pConstants.RENDEZVOUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build();

    private P2pTurnCredentialClient() {
    }

    public static P2pTurnCredentials fetch(String role, boolean turnOnly) throws IOException {
        if (!P2pConstants.hasRendezvousUrl()) {
            throw new IOException("TURN icin rendezvous URL gerekli");
        }

        URI uri = turnCredentialsUri(role, turnOnly);
        Request.Builder builder = new Request.Builder()
            .url(uri.toString())
            .get();
        String token = P2pConstants.rendezvousToken();
        if (token != null && !token.trim().isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }

        Response response = HTTP_CLIENT.newCall(builder.build()).execute();
        try {
            if (response.code() < 200 || response.code() >= 300) {
                throw new IOException("TURN credential request returned HTTP " + response.code());
            }

            String body = response.body() != null ? response.body().string() : "";
            JsonObject json;
            try {
                json = new JsonParser().parse(body).getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new IOException("TURN credential response is invalid JSON", exception);
            }
            return parse(json);
        } finally {
            response.close();
        }
    }

    public static P2pTurnCredentials parse(JsonObject json) throws IOException {
        JsonArray iceServers = json.getAsJsonArray("iceServers");
        if (iceServers == null || iceServers.size() == 0) {
            throw new IOException("TURN credential response did not include iceServers");
        }

        Set<P2pTurnCredentials.TurnServer> udpServers = new LinkedHashSet<>();
        Set<P2pTurnCredentials.TurnServer> tcpServers = new LinkedHashSet<>();
        Set<P2pTurnCredentials.TurnServer> tlsServers = new LinkedHashSet<>();
        String username = "";
        String credential = "";
        for (JsonElement serverElement : iceServers) {
            if (!serverElement.isJsonObject()) {
                continue;
            }

            JsonObject server = serverElement.getAsJsonObject();
            String candidateUsername = string(server, "username");
            String candidateCredential = string(server, "credential");
            if (candidateUsername != null && !candidateUsername.trim().isEmpty()
                && candidateCredential != null && !candidateCredential.trim().isEmpty()) {
                username = candidateUsername;
                credential = candidateCredential;
            }

            JsonElement urlsElement = server.get("urls");
            if (urlsElement == null || urlsElement.isJsonNull()) {
                continue;
            }
            if (urlsElement.isJsonPrimitive()) {
                addServer(urlsElement.getAsString(), udpServers, tcpServers, tlsServers);
                continue;
            }
            if (urlsElement.isJsonArray()) {
                for (JsonElement urlElement : urlsElement.getAsJsonArray()) {
                    if (urlElement != null && urlElement.isJsonPrimitive()) {
                        addServer(urlElement.getAsString(), udpServers, tcpServers, tlsServers);
                    }
                }
            }
        }

        if (username.trim().isEmpty() || credential.trim().isEmpty()) {
            throw new IOException("TURN credential response did not include username or credential");
        }
        if (udpServers.isEmpty()) {
            throw new IOException("TURN credential response did not include a UDP TURN server");
        }

        addCloudflareStreamFallbacks(udpServers, tcpServers, tlsServers);

        return new P2pTurnCredentials(
            new ArrayList<P2pTurnCredentials.TurnServer>(udpServers),
            new ArrayList<P2pTurnCredentials.TurnServer>(tcpServers),
            new ArrayList<P2pTurnCredentials.TurnServer>(tlsServers),
            username,
            credential,
            integer(json.get("ttl"), P2pConstants.TURN_DEFAULT_CREDENTIAL_TTL_SECONDS)
        );
    }

    private static URI turnCredentialsUri(String role, boolean turnOnly) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
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
        String identifier = "safra-" + role + "-" + SafraBuildInfo.minecraftVersion() + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String query = "mode=" + encode(turnOnly ? "turn-only" : "auto")
            + "&ttl=" + P2pConstants.turnCredentialTtlSeconds()
            + "&customIdentifier=" + encode(identifier);
        return URI.create(scheme + "://" + baseUri.getAuthority() + "/v3/turn/credentials?" + query);
    }

    private static void addServer(String rawUrl, Set<P2pTurnCredentials.TurnServer> udpServers,
                                  Set<P2pTurnCredentials.TurnServer> tcpServers,
                                  Set<P2pTurnCredentials.TurnServer> tlsServers) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return;
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.replaceFirst("^turn:", "turn://").replaceFirst("^turns:", "turns://"));
        } catch (RuntimeException exception) {
            return;
        }

        String scheme = uri.getScheme();
        if (!"turn".equalsIgnoreCase(scheme) && !"turns".equalsIgnoreCase(scheme)) {
            return;
        }

        String query = uri.getQuery();
        String transport = query == null || query.trim().isEmpty() ? null : queryParameter(query, "transport");

        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            return;
        }

        P2pTurnCredentials.TurnServer turnServer = new P2pTurnCredentials.TurnServer(host, port);
        if ("turns".equalsIgnoreCase(scheme)) {
            tlsServers.add(turnServer);
        } else if ("tcp".equalsIgnoreCase(transport)) {
            tcpServers.add(turnServer);
        } else if (transport == null || "udp".equalsIgnoreCase(transport)) {
            udpServers.add(turnServer);
        }
    }

    private static void addCloudflareStreamFallbacks(Set<P2pTurnCredentials.TurnServer> udpServers,
                                                       Set<P2pTurnCredentials.TurnServer> tcpServers,
                                                       Set<P2pTurnCredentials.TurnServer> tlsServers) {
        boolean cloudflare = false;
        for (P2pTurnCredentials.TurnServer server : udpServers) {
            if ("turn.cloudflare.com".equalsIgnoreCase(server.host())) {
                cloudflare = true;
                break;
            }
        }
        if (!cloudflare) {
            return;
        }
        tlsServers.add(new P2pTurnCredentials.TurnServer("turn.cloudflare.com", 443));
        tlsServers.add(new P2pTurnCredentials.TurnServer("turn.cloudflare.com", 5349));
        tcpServers.add(new P2pTurnCredentials.TurnServer("turn.cloudflare.com", 80));
        tcpServers.add(new P2pTurnCredentials.TurnServer("turn.cloudflare.com", 3478));
    }

    private static String queryParameter(String query, String key) {
        String prefix = key + "=";
        for (String segment : query.split("&")) {
            if (segment.startsWith(prefix)) {
                return segment.substring(prefix.length());
            }
        }
        return null;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static int integer(JsonElement element, int fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }

        try {
            return element.getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
