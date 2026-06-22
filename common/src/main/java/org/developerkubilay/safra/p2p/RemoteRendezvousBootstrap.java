package org.developerkubilay.safra.p2p;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.Logger;
import org.developerkubilay.safra.util.SafraLogger;

import java.util.concurrent.TimeUnit;

public final class RemoteRendezvousBootstrap {
    private static final Logger LOGGER = SafraLogger.get(RemoteRendezvousBootstrap.class);
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/DeveloperKubilay/Safra/refs/heads/assets/config.json";
    private static final String DEFAULT_SITE_API_VERSION = "3.0";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build();

    private RemoteRendezvousBootstrap() {
    }

    public static void initializeDedicated() {
        if (hasExplicitRendezvousUrlOverride()) {
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
                String remoteUrl = RemoteRendezvousConfigParser.parseRemoteUrl(body, siteApiVersion(), "dedicated");
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

    private static boolean hasExplicitRendezvousUrlOverride() {
        String property = System.getProperty("safra.rendezvousUrl");
        if (property != null && !property.trim().isEmpty()) {
            return true;
        }

        String environment = System.getenv("SAFRA_RENDEZVOUS_URL");
        if (environment != null && !environment.trim().isEmpty()) {
            return true;
        }

        String legacyEnvironment = System.getenv("SAFRA_SIGNALING_URL");
        return legacyEnvironment != null && !legacyEnvironment.trim().isEmpty();
    }
}
