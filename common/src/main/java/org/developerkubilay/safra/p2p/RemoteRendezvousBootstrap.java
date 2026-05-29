package org.developerkubilay.safra.p2p;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class RemoteRendezvousBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteRendezvousBootstrap.class);
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/DeveloperKubilay/Safra/refs/heads/assets/config.json";
    private static final String DEFAULT_SITE_API_VERSION = "1.0";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build();

    private RemoteRendezvousBootstrap() {
    }

    public static void initialize() {
        if (P2pConstants.hasRendezvousUrl()) {
            return;
        }

        try {
            Request request = new Request.Builder()
                .url(REMOTE_CONFIG_URL)
                .get()
                .build();

            Response response = HTTP_CLIENT.newCall(request).execute();
            try {
                if (!response.isSuccessful()) {
                    LOGGER.debug("Safra remote rendezvous config request returned HTTP {}", response.code());
                    return;
                }

                String body = response.body() != null ? response.body().string() : "";
                String remoteUrl = parseRemoteUrl(body, siteApiVersion());
                if (!P2pConstants.isValidRendezvousUrl(remoteUrl)) {
                    LOGGER.debug("Safra remote rendezvous config did not contain a valid URL for api-{}", siteApiVersion());
                    return;
                }

                P2pConstants.setRuntimeRendezvousUrl(remoteUrl);
            } finally {
                response.close();
            }
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
