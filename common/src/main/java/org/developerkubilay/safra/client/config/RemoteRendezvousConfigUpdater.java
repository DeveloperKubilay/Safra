package org.developerkubilay.safra.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.SafraBuildInfo;
import org.developerkubilay.safra.util.SafraLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteRendezvousConfigUpdater {
    private static final Logger LOGGER = SafraLogger.get(RemoteRendezvousConfigUpdater.class);
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/DeveloperKubilay/Safra/refs/heads/assets/config.json";
    private static final int TIMEOUT_MS = 5000;
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

        Thread thread = new Thread(() -> {
            try {
                String body = httpGet(REMOTE_CONFIG_URL);
                applyRemoteConfig(config, body);
            } catch (Exception throwable) {
                LOGGER.debug("Safra remote rendezvous config refresh skipped: {}", throwable.toString());
            }
        });
        thread.setDaemon(true);
        thread.setName("safra-config-updater");
        thread.start();
    }

    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static void applyRemoteConfig(BaseSafraClientConfig config, String body) {
        try {
            if (body == null || body.trim().isEmpty()) {
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
        if (latest == null || latest.trim().isEmpty()) {
            return false;
        }

        String current = SafraBuildInfo.modVersion();
        if (current == null || current.trim().isEmpty() || "unknown".equalsIgnoreCase(current)) {
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
        if (minecraftVersion == null || minecraftVersion.trim().isEmpty() || "unknown".equalsIgnoreCase(minecraftVersion)) {
            return "";
        }

        JsonElement versionElement = latestElement.getAsJsonObject().get(minecraftVersion.trim());
        if (versionElement == null || versionElement.isJsonNull()) {
            return "";
        }

        String remoteVersion = versionElement.getAsString();
        return remoteVersion == null ? "" : remoteVersion.trim();
    }
}
