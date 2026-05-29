package org.developerkubilay.safra.p2p.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.SafraBuildInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class P2pTurnCredentialClient {
    private static final int TIMEOUT_MS = (int) P2pConstants.RENDEZVOUS_TIMEOUT_MS;

    private P2pTurnCredentialClient() {
    }

    public static P2pTurnCredentials fetch(String role, boolean turnOnly) throws IOException {
        if (!P2pConstants.hasRendezvousUrl()) {
            throw new IOException("TURN icin rendezvous URL gerekli");
        }

        URI uri = turnCredentialsUri(role, turnOnly);
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        String token = P2pConstants.rendezvousToken();
        if (!token.trim().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("TURN credential istegi HTTP " + statusCode + " dondu");
        }

        StringBuilder bodyBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                bodyBuilder.append(line);
            }
        }

        JsonObject json;
        try {
            json = new JsonParser().parse(bodyBuilder.toString()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("TURN credential cevabi gecersiz JSON", exception);
        }

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
            if (!candidateUsername.trim().isEmpty() && !candidateCredential.trim().isEmpty()) {
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

        if (username.trim().isEmpty() || credential.trim().isEmpty()) {
            throw new IOException("TURN credential cevabinda username/credential eksik");
        }
        if (udpServers.isEmpty()) {
            throw new IOException("TURN credential cevabinda UDP TURN sunucusu yok");
        }

        return new P2pTurnCredentials(
            new ArrayList<P2pTurnCredentials.TurnServer>(udpServers),
            username,
            credential,
            integer(json.get("ttl"), P2pConstants.TURN_DEFAULT_CREDENTIAL_TTL_SECONDS)
        );
    }

    private static URI turnCredentialsUri(String role, boolean turnOnly) {
        String base = P2pConstants.rendezvousUrl().replaceAll("/+$", "");
        URI baseUri = URI.create(base);
        String schemeRaw = baseUri.getScheme().toLowerCase(Locale.ROOT);
        String scheme;
        if ("http".equals(schemeRaw) || "https".equals(schemeRaw)) {
            scheme = schemeRaw;
        } else if ("ws".equals(schemeRaw)) {
            scheme = "http";
        } else if ("wss".equals(schemeRaw)) {
            scheme = "https";
        } else {
            throw new IllegalArgumentException("unsupported rendezvous URL scheme: " + baseUri.getScheme());
        }
        String identifier = "safra-" + role + "-" + SafraBuildInfo.minecraftVersion() + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String query = "mode=" + encode(turnOnly ? "turn-only" : "auto")
            + "&ttl=" + P2pConstants.turnCredentialTtlSeconds()
            + "&customIdentifier=" + encode(identifier);
        return URI.create(scheme + "://" + baseUri.getAuthority() + "/v2/turn/credentials?" + query);
    }

    private static void addUdpServer(String rawUrl, Set<P2pTurnCredentials.TurnServer> udpServers) {
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
        if (!"turn".equalsIgnoreCase(scheme)) {
            return;
        }

        String query = uri.getQuery();
        if (query != null && !query.trim().isEmpty()) {
            String transport = queryParameter(query, "transport");
            if (transport != null && !"udp".equalsIgnoreCase(transport)) {
                return;
            }
        }

        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
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
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new RuntimeException("UTF-8 not supported", exception);
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
