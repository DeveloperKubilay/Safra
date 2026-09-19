package org.developerkubilay.safra.p2p;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class RemoteRendezvousBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteRendezvousBootstrap.class);
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/DeveloperKubilay/Safra/refs/heads/assets/config.json";
    private static final String DEFAULT_SITE_API_VERSION = "3.0";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();

    private RemoteRendezvousBootstrap() {
    }

    public static void initializeDedicated() {
        if (P2pConstants.hasExplicitRendezvousUrlOverride()) {
            return;
        }

        P2pConstants.applyDefaultRendezvousUrlIfAbsent();

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

                String apiVersion = siteApiVersion();
                String body = response.body() != null ? response.body().string() : "";
                String remoteUrl = RemoteRendezvousConfigParser.parseRemoteUrl(body, apiVersion, "dedicated");
                if (!P2pConstants.isValidRendezvousUrl(remoteUrl)) {
                    LOGGER.debug("Safra remote rendezvous config did not contain a valid URL for api-{}", apiVersion);
                    return;
                }

                P2pConstants.setRuntimeSiteApiVersion(apiVersion);
                P2pConstants.setRuntimeRendezvousUrl(remoteUrl);
            } finally {
                response.close();
            }
        } catch (Exception exception) {
            LOGGER.debug("Safra remote rendezvous bootstrap skipped: {}", exception.toString());
        }
    }

    private static String siteApiVersion() {
        String resolved = P2pConstants.siteApiVersion();
        return resolved == null || resolved.trim().isEmpty() ? DEFAULT_SITE_API_VERSION : resolved;
    }
}
