package org.developerkubilay.safra.p2p;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class RemoteRendezvousBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteRendezvousBootstrap.class);
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/DeveloperKubilay/Safra/refs/heads/assets/config.json";
    private static final String DEFAULT_SITE_API_VERSION = "1.0";
    private static final String DEFAULT_API_1_URL = "https://safra.developerkubilay.workers.dev";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build();

    private RemoteRendezvousBootstrap() {
    }

    public static void initialize() {
        if (P2pConstants.hasRendezvousUrl()) {
            return;
        }

        String siteApiVersion = siteApiVersion();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(REMOTE_CONFIG_URL))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.debug("Safra remote rendezvous config request returned HTTP {}", response.statusCode());
                applyEmbeddedFallback(siteApiVersion);
                return;
            }

            String remoteUrl = parseRemoteUrl(response.body(), siteApiVersion);
            if (!P2pConstants.isValidRendezvousUrl(remoteUrl)) {
                LOGGER.debug("Safra remote rendezvous config did not contain a valid URL for api-{}", siteApiVersion);
                applyEmbeddedFallback(siteApiVersion);
                return;
            }

            P2pConstants.setRuntimeRendezvousUrl(remoteUrl);
        } catch (Exception exception) {
            LOGGER.debug("Safra remote rendezvous bootstrap skipped: {}", exception.toString());
            applyEmbeddedFallback(siteApiVersion);
        }
    }

    private static String parseRemoteUrl(String body, String siteApiVersion) {
        if (body == null || body.isBlank()) {
            return "";
        }

        JsonObject json = new JsonParser().parse(body).getAsJsonObject();
        JsonElement urlElement = json.get("api-" + siteApiVersion);
        if (urlElement == null || urlElement.isJsonNull()) {
            return "";
        }

        return urlElement.getAsString();
    }

    private static String siteApiVersion() {
        String property = System.getProperty("safra.siteApiVersion");
        if (property != null && !property.isBlank()) {
            return property.trim();
        }

        String environment = System.getenv("SAFRA_SITE_API_VERSION");
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }

        return DEFAULT_SITE_API_VERSION;
    }

    private static void applyEmbeddedFallback(String siteApiVersion) {
        String fallbackUrl = embeddedFallbackUrl(siteApiVersion);
        if (!P2pConstants.isValidRendezvousUrl(fallbackUrl)) {
            return;
        }

        LOGGER.debug("Safra remote rendezvous bootstrap is using embedded fallback for api-{}", siteApiVersion);
        P2pConstants.setRuntimeRendezvousUrl(fallbackUrl);
    }

    private static String embeddedFallbackUrl(String siteApiVersion) {
        if (DEFAULT_SITE_API_VERSION.equals(siteApiVersion)) {
            return DEFAULT_API_1_URL;
        }

        return "";
    }
}
