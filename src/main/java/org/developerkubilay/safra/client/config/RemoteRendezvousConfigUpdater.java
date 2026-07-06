package org.developerkubilay.safra.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.SafraBuildInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private RemoteRendezvousConfigUpdater() {
    }

    public static void initialize(BaseSafraClientConfig config) {
        if (config == null) {
            return;
        }

        P2pConstants.setRuntimeRendezvousUrl(config.getRendezvousUrl());
        P2pConstants.setRuntimeNeverUseRelayServer(config.isNeverUseRelayServer());
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

            JsonObject json = new JsonParser().parse(body).getAsJsonObject();
            latestModVersion = parseLatestModVersion(json);
            String key = "api-" + config.getSiteApiVersion();
            JsonElement urlElement = json.get(key);
            if (urlElement == null || urlElement.isJsonNull()) {
                return;
            }

            String remoteUrl = urlElement.getAsString();
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
        String latest = latestModVersion;
        if (latest == null || latest.isBlank()) {
            return false;
        }

        String current = SafraBuildInfo.modVersion();
        if (current == null || current.isBlank() || "unknown".equalsIgnoreCase(current)) {
            return false;
        }

        return !latest.trim().equals(current.trim());
    }

    private static String parseLatestModVersion(JsonObject json) {
        if (json == null) {
            return "";
        }

        JsonElement latestElement = json.get("latest");
        if (latestElement == null || latestElement.isJsonNull() || !latestElement.isJsonObject()) {
            return "";
        }

        String minecraftVersion = SafraBuildInfo.minecraftVersion();
        if (minecraftVersion == null || minecraftVersion.isBlank() || "unknown".equalsIgnoreCase(minecraftVersion)) {
            return "";
        }

        JsonElement versionElement = latestElement.getAsJsonObject().get(minecraftVersion.trim());
        if (versionElement == null || versionElement.isJsonNull()) {
            return "";
        }

        if (versionElement.isJsonArray()) {
            return versionElement.getAsJsonArray().get(versionElement.getAsJsonArray().size() - 1).getAsString();
        }

        String remoteVersion = versionElement.getAsString();
        return remoteVersion == null ? "" : remoteVersion.trim();
    }
}
