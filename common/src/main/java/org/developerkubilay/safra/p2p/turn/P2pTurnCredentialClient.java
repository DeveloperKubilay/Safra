package org.developerkubilay.safra.p2p.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.developerkubilay.safra.p2p.P2pConstants;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class P2pTurnCredentialClient {
    private P2pTurnCredentialClient() {
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
            if (!candidateUsername.isBlank() && !candidateCredential.isBlank()) {
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

        if (username.isBlank() || credential.isBlank()) {
            throw new IOException("TURN credential response is missing username/credential");
        }
        if (udpServers.isEmpty()) {
            throw new IOException("TURN credential response did not include a UDP TURN server");
        }

        addCloudflareStreamFallbacks(udpServers, tcpServers, tlsServers);

        return new P2pTurnCredentials(
            List.copyOf(udpServers),
            List.copyOf(tcpServers),
            List.copyOf(tlsServers),
            username,
            credential,
            integer(json.get("ttl"), P2pConstants.TURN_DEFAULT_CREDENTIAL_TTL_SECONDS)
        );
    }

    private static void addServer(String rawUrl, Set<P2pTurnCredentials.TurnServer> udpServers,
                                  Set<P2pTurnCredentials.TurnServer> tcpServers,
                                  Set<P2pTurnCredentials.TurnServer> tlsServers) {
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
        if (!"turn".equalsIgnoreCase(scheme) && !"turns".equalsIgnoreCase(scheme)) {
            return;
        }

        String query = uri.getQuery();
        String transport = query == null || query.isBlank() ? null : queryParameter(query, "transport");

        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
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
        boolean cloudflare = udpServers.stream().anyMatch(server -> "turn.cloudflare.com".equalsIgnoreCase(server.host()));
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
