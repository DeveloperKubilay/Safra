package org.developerkubilay.safra.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.RemoteRendezvousConfigParser;
import org.developerkubilay.safra.p2p.SafraBuildInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteRendezvousConfigUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteRendezvousConfigUpdater.class);
    private static final String DEFAULT_DISCORD_URL = "https://discord.gg/NHjBvRxDXP";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile String latestModVersion = "";
    private static volatile List<String> latestModVersions = List.of();
    private static volatile String discordUrl = DEFAULT_DISCORD_URL;
    private static volatile String youtubeUrl = "";

    private RemoteRendezvousConfigUpdater() {
    }

    public static void initialize(BaseSafraClientConfig config) {
        if (config == null) {
            return;
        }

        boolean testOnly = "test-only".equalsIgnoreCase(config.getSiteApiVersion());
        if (!P2pConstants.hasExplicitRendezvousUrlOverride() && !testOnly
            && !P2pConstants.DEFAULT_RENDEZVOUS_URL.equals(config.getRendezvousUrl())) {
            config.setRendezvousUrl(P2pConstants.DEFAULT_RENDEZVOUS_URL);
        }
        P2pConstants.setRuntimeRendezvousUrl(config.getRendezvousUrl());
        P2pConstants.setRuntimeNeverUseRelayServer(config.isNeverUseRelayServer());
        P2pConstants.setRuntimeSiteApiVersion(config.getSiteApiVersion());
        if (!testOnly) {
            P2pConstants.applyDefaultRendezvousUrlIfAbsent();
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        fetchRemoteConfig(config, 0);
    }

    private static void fetchRemoteConfig(BaseSafraClientConfig config, int index) {
        if (index >= P2pConstants.REMOTE_CONFIG_URLS.size()) {
            return;
        }

        String url = P2pConstants.REMOTE_CONFIG_URLS.get(index);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", SafraBuildInfo.userAgent())
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        } catch (RuntimeException exception) {
            fetchRemoteConfig(config, index + 1);
            return;
        }

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300 && response.body() != null && !response.body().isBlank()) {
                    if (applyRemoteConfig(config, response.body())) {
                        return;
                    }
                }
                fetchRemoteConfig(config, index + 1);
            })
            .exceptionally(throwable -> {
                LOGGER.debug("Safra remote rendezvous config refresh failed for {}: {}", url, throwable.toString());
                fetchRemoteConfig(config, index + 1);
                return null;
            });
    }

    private static boolean applyRemoteConfig(BaseSafraClientConfig config, String body) {
        try {
            if (body == null || body.isBlank()) {
                return false;
            }

            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonElement discordElement = json.get("discord");
            if (discordElement != null && discordElement.isJsonPrimitive() && isValidDiscordUrl(discordElement.getAsString())) {
                discordUrl = discordElement.getAsString().trim();
            }
            JsonElement youtubeElement = json.get("youtube");
            if (youtubeElement != null && youtubeElement.isJsonPrimitive()) {
                String value = youtubeElement.getAsString().trim();
                youtubeUrl = isValidYoutubeUrl(value) ? value : "";
            } else {
                youtubeUrl = "";
            }
            List<String> latestVersions = parseLatestModVersions(json);
            latestModVersions = latestVersions;
            if (latestVersions.isEmpty()) {
                latestModVersion = "";
            } else {
                latestModVersion = latestVersions.get(latestVersions.size() - 1);
            }
            String remoteUrl = RemoteRendezvousConfigParser.parseRemoteUrl(json, config.getSiteApiVersion(), "client");
            if (!P2pConstants.isValidRendezvousUrl(remoteUrl)) {
                if (!"test-only".equalsIgnoreCase(config.getSiteApiVersion())) {
                    config.setRendezvousUrl("");
                    P2pConstants.applyDefaultRendezvousUrlIfAbsent();
                }
                return false;
            }

            config.setRendezvousUrl(remoteUrl);
            P2pConstants.setRuntimeRendezvousUrl(remoteUrl);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.debug("Safra remote rendezvous config could not be applied: {}", exception.toString());
            return false;
        }
    }

    public static String latestModVersion() {
        return latestModVersion;
    }

    public static String discordUrl() {
        return discordUrl;
    }

    public static String youtubeUrl() {
        return youtubeUrl;
    }

    private static boolean isValidDiscordUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isValidYoutubeUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                && host != null
                && ("youtube.com".equalsIgnoreCase(host)
                    || host.toLowerCase(Locale.ROOT).endsWith(".youtube.com")
                    || "youtu.be".equalsIgnoreCase(host));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
