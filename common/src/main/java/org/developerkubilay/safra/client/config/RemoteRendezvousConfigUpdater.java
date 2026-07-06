package org.developerkubilay.safra.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.RemoteRendezvousConfigParser;
import org.developerkubilay.safra.p2p.SafraBuildInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteRendezvousConfigUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteRendezvousConfigUpdater.class);
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/DeveloperKubilay/Safra/refs/heads/assets/config.json";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile String latestModVersion = "";
    private static volatile List<String> latestModVersions = List.of();

    private RemoteRendezvousConfigUpdater() {
    }

    public static void initialize(BaseSafraClientConfig config) {
        if (config == null) {
            return;
        }

        P2pConstants.setRuntimeRendezvousUrl(config.getRendezvousUrl());
        P2pConstants.setRuntimeNeverUseRelayServer(config.isNeverUseRelayServer());
        P2pConstants.setRuntimeSiteApiVersion(config.getSiteApiVersion());
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(REMOTE_CONFIG_URL))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300 ? response.body() : "")
            .thenAccept(body -> applyRemoteConfig(config, body))
            .exceptionally(throwable -> {
                LOGGER.debug("Safra remote rendezvous config refresh skipped: {}", throwable.toString());
                return null;
            });
    }

    private static void applyRemoteConfig(BaseSafraClientConfig config, String body) {
        try {
            if (body == null || body.isBlank()) {
                return;
            }

            JsonObject json = new com.google.gson.JsonParser().parse(body).getAsJsonObject();
            List<String> latestVersions = parseLatestModVersions(json);
            latestModVersions = latestVersions;
            if (latestVersions.isEmpty()) {
                latestModVersion = "";
            } else {
                latestModVersion = latestVersions.get(latestVersions.size() - 1);
            }
            String remoteUrl = RemoteRendezvousConfigParser.parseRemoteUrl(json, config.getSiteApiVersion(), "client");
            if (!P2pConstants.isValidRendezvousUrl(remoteUrl)) {
                config.setRendezvousUrl("");
                P2pConstants.setRuntimeRendezvousUrl(null);
                return;
            }

            config.setRendezvousUrl(remoteUrl);
            P2pConstants.setRuntimeRendezvousUrl(remoteUrl);
        } catch (RuntimeException exception) {
            LOGGER.debug("Safra remote rendezvous config could not be applied: {}", exception.toString());
        }
    }

    public static String latestModVersion() {
        return latestModVersion;
    }

    public static boolean hasNewerModVersion() {
        List<String> latest = latestModVersions;
        if (latest == null || latest.isEmpty()) {
            return false;
        }

        String current = SafraBuildInfo.modVersion();
        if (current == null || current.isBlank() || "unknown".equalsIgnoreCase(current)) {
            return false;
        }

        String normalizedCurrent = current.trim();
        for (String version : latest) {
            if (normalizedCurrent.equals(version)) {
                return false;
            }
        }

        return true;
    }

    private static List<String> parseLatestModVersions(JsonObject json) {
        if (json == null) {
            return List.of();
        }

        JsonElement latestElement = json.get("latest");
        if (latestElement == null || latestElement.isJsonNull() || !latestElement.isJsonObject()) {
            return List.of();
        }

        String minecraftVersion = SafraBuildInfo.minecraftVersion();
        if (minecraftVersion == null || minecraftVersion.isBlank() || "unknown".equalsIgnoreCase(minecraftVersion)) {
            return List.of();
        }

        JsonElement versionElement = latestElement.getAsJsonObject().get(minecraftVersion.trim());
        if (versionElement == null || versionElement.isJsonNull()) {
            return List.of();
        }

        LinkedHashSet<String> versions = new LinkedHashSet<>();
        if (versionElement.isJsonPrimitive()) {
            addVersion(versions, versionElement);
        } else if (versionElement.isJsonArray()) {
            versionElement.getAsJsonArray().forEach(element -> addVersion(versions, element));
        }

        return versions.isEmpty() ? List.of() : new ArrayList<>(versions);
    }

    private static void addVersion(LinkedHashSet<String> versions, JsonElement versionElement) {
        if (versionElement == null || versionElement.isJsonNull() || !versionElement.isJsonPrimitive()) {
            return;
        }

        String remoteVersion = versionElement.getAsString();
        if (remoteVersion == null) {
            return;
        }

        String normalized = remoteVersion.trim();
        if (!normalized.isBlank()) {
            versions.add(normalized);
        }
    }
}
