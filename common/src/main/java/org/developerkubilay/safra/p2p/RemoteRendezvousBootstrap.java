package org.developerkubilay.safra.p2p;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;
import org.developerkubilay.safra.util.SafraLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class RemoteRendezvousBootstrap {
    private static final Logger LOGGER = SafraLogger.get(RemoteRendezvousBootstrap.class);
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/DeveloperKubilay/Safra/refs/heads/assets/config.json";
    private static final String DEFAULT_SITE_API_VERSION = "1.0";
    private static final int TIMEOUT_MS = 5000;

    private RemoteRendezvousBootstrap() {
    }

    public static void initialize() {
        if (P2pConstants.hasRendezvousUrl()) {
            return;
        }

        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(REMOTE_CONFIG_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                LOGGER.debug("Safra remote rendezvous config request returned HTTP {}", statusCode);
                return;
            }

            StringBuilder bodyBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    bodyBuilder.append(line);
                }
            }

            String remoteUrl = parseRemoteUrl(bodyBuilder.toString(), siteApiVersion());
            if (!P2pConstants.isValidRendezvousUrl(remoteUrl)) {
                LOGGER.debug("Safra remote rendezvous config did not contain a valid URL for api-{}", siteApiVersion());
                return;
            }

            P2pConstants.setRuntimeRendezvousUrl(remoteUrl);
        } catch (Exception exception) {
            LOGGER.debug("Safra remote rendezvous bootstrap skipped: {}", exception.toString());
        }
    }

    private static String parseRemoteUrl(String body, String siteApiVersion) {
        if (body == null || body.trim().isEmpty()) {
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
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }

        String environment = System.getenv("SAFRA_SITE_API_VERSION");
        if (environment != null && !environment.trim().isEmpty()) {
            return environment.trim();
        }

        return DEFAULT_SITE_API_VERSION;
    }
}
