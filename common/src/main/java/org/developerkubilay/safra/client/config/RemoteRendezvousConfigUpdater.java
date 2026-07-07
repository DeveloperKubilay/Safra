package org.developerkubilay.safra.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.RemoteRendezvousConfigParser;
import org.developerkubilay.safra.p2p.SafraBuildInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteRendezvousConfigUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteRendezvousConfigUpdater.class);
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/DeveloperKubilay/Safra/refs/heads/assets/config.json";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile String latestModVersion = "";
    private static volatile List<String> latestModVersions = java.util.Collections.emptyList();

    private RemoteRendezvousConfigUpdater() {
    }

    public static void initialize(BaseSafraClientConfig config) {
        if (config == null) {
            return;
        }

        if (config.getRendezvousUrl().trim().isEmpty() && !P2pConstants.hasExplicitRendezvousUrlOverride()) {
            config.setRendezvousUrl(P2pConstants.DEFAULT_RENDEZVOUS_URL);
        }
        P2pConstants.setRuntimeRendezvousUrl(config.getRendezvousUrl());
        P2pConstants.setRuntimeNeverUseRelayServer(config.isNeverUseRelayServer());
        P2pConstants.applyDefaultRendezvousUrlIfAbsent();
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        Request request = new Request.Builder()
            .url(REMOTE_CONFIG_URL)
            .get()
            .build();

        HTTP_CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException exception) {
                LOGGER.debug("Safra remote rendezvous config refresh skipped: {}", exception.toString());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        applyRemoteConfig(config, response.body().string());
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private static void applyRemoteConfig(BaseSafraClientConfig config, String body) {
        try {
            if (body == null || body.trim().isEmpty()) {
                return;
            }

            JsonObject json = new JsonParser().parse(body).getAsJsonObject();
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
                P2pConstants.applyDefaultRendezvousUrlIfAbsent();
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
        if (current == null || current.trim().isEmpty() || "unknown".equalsIgnoreCase(current)) {
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
            return java.util.Collections.emptyList();
        }

        JsonElement latestElement = json.get("latest");
        if (latestElement == null || latestElement.isJsonNull() || !latestElement.isJsonObject()) {
            return java.util.Collections.emptyList();
        }

        String minecraftVersion = SafraBuildInfo.minecraftVersion();
        if (minecraftVersion == null || minecraftVersion.trim().isEmpty() || "unknown".equalsIgnoreCase(minecraftVersion)) {
            return java.util.Collections.emptyList();
        }

        JsonElement versionElement = latestElement.getAsJsonObject().get(minecraftVersion.trim());
        if (versionElement == null || versionElement.isJsonNull()) {
            return java.util.Collections.emptyList();
        }

        LinkedHashSet<String> versions = new LinkedHashSet<>();
        if (versionElement.isJsonPrimitive()) {
            addVersion(versions, versionElement);
        } else if (versionElement.isJsonArray()) {
            for (JsonElement element : versionElement.getAsJsonArray()) {
                addVersion(versions, element);
            }
        }

        return versions.isEmpty() ? java.util.Collections.<String>emptyList() : new ArrayList<>(versions);
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
        if (!normalized.trim().isEmpty()) {
            versions.add(normalized);
        }
    }
}
