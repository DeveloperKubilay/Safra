package org.developerkubilay.safra.p2p.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.SafraBuildInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class P2pTurnCredentialClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_TIMEOUT_MS))
        .build();

    private P2pTurnCredentialClient() {
    }

    public static P2pTurnCredentials fetch(String role, boolean turnOnly) throws IOException {
        if (!P2pConstants.hasRendezvousUrl()) {
            throw new IOException("TURN icin rendezvous URL gerekli");
        }

        URI uri = turnCredentialsUri(role, turnOnly);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(P2pConstants.RENDEZVOUS_TIMEOUT_MS))
            .GET();
        String token = P2pConstants.rendezvousToken();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("TURN credential istegi yarida kesildi", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("TURN credential istegi HTTP " + response.statusCode() + " dondu");
        }

        JsonObject json;
        try {
            json = new JsonParser().parse(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("TURN credential cevabi gecersiz JSON", exception);
        }

        return parse(json);
    }

    public static P2pTurnCredentials parse(JsonObject json) throws IOException {
        JsonArray iceServers = json.getAsJsonArray("iceServers");
        if (iceServers == null || iceServers.size() == 0) {
            throw new IOException("TURN credential cevabinda iceServers yok");
        }

        Set<P2pTurnCredentials.TurnServer> udpServers = new LinkedHashSet<>();
        String username = "";
        String credential = "";
        for (JsonElement serverElement : iceServers) {
            if (!serverElement.isJsonObject()) {
                continue;
            }

            JsonObject server = serverElement.getAsJsonObject();
            String candidateUsername = string(server, "username");
            String candidateCredential = string(server, "credential");
            if (!candidateUsername.isBlank() && !candidateCredential.isBlank()) {
                username = candidateUsername;
                credential = candidateCredential;
            }

            JsonElement urlsElement = server.get("urls");
            if (urlsElement == null || urlsElement.isJsonNull()) {
                continue;
            }
            if (urlsElement.isJsonPrimitive()) {
                addUdpServer(urlsElement.getAsString(), udpServers);
                continue;
            }
            if (urlsElement.isJsonArray()) {
                for (JsonElement urlElement : urlsElement.getAsJsonArray()) {
                    if (urlElement != null && urlElement.isJsonPrimitive()) {
                        addUdpServer(urlElement.getAsString(), udpServers);
                    }
                }
            }
        }

        if (username.isBlank() || credential.isBlank()) {
            throw new IOException("TURN credential cevabinda username/credential eksik");
        }
        if (udpServers.isEmpty()) {
            throw new IOException("TURN credential cevabinda UDP TURN sunucusu yok");
        }

        return new P2pTurnCredentials(
            List.copyOf(udpServers),
            username,
            credential,
            integer(json.get("ttl"), P2pConstants.TURN_DEFAULT_CREDENTIAL_TTL_SECONDS)
        );
    }

    private static URI turnCredentialsUri(String role, boolean turnOnly) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
        URI baseUri = URI.create(base);
        String scheme = switch (baseUri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http", "https" -> baseUri.getScheme().toLowerCase(Locale.ROOT);
            case "ws" -> "http";
            case "wss" -> "https";
            default -> throw new IllegalArgumentException("unsupported rendezvous URL scheme: " + baseUri.getScheme());
        };
        String identifier = "safra-" + role + "-" + SafraBuildInfo.minecraftVersion() + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String query = "mode=" + encode(turnOnly ? "turn-only" : "auto")
            + "&ttl=" + P2pConstants.turnCredentialTtlSeconds()
            + "&customIdentifier=" + encode(identifier);
        return URI.create(scheme + "://" + baseUri.getAuthority() + "/v3/turn/credentials?" + query);
    }

    private static void addUdpServer(String rawUrl, Set<P2pTurnCredentials.TurnServer> udpServers) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.replaceFirst("^turn:", "turn://").replaceFirst("^turns:", "turns://"));
        } catch (RuntimeException exception) {
            return;
        }

        String scheme = uri.getScheme();
        if (!"turn".equalsIgnoreCase(scheme)) {
            return;
        }

        String query = uri.getQuery();
        if (query != null && !query.isBlank()) {
            String transport = queryParameter(query, "transport");
            if (transport != null && !"udp".equalsIgnoreCase(transport)) {
                return;
            }
        }

        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            return;
        }

        udpServers.add(new P2pTurnCredentials.TurnServer(host, port));
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
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
