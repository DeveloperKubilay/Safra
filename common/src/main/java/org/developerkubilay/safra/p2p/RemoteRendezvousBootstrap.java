package org.developerkubilay.safra.p2p;
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
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build();

    private RemoteRendezvousBootstrap() {
    }

    public static void initializeDedicated() {
        if (P2pConstants.hasExplicitRendezvousUrlOverride()) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(REMOTE_CONFIG_URL))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.debug("Safra remote rendezvous config request returned HTTP {}", response.statusCode());
                return;
            }

            String apiVersion = siteApiVersion();
            String remoteUrl = RemoteRendezvousConfigParser.parseRemoteUrl(response.body(), apiVersion, "dedicated");
            if (!P2pConstants.isValidRendezvousUrl(remoteUrl)) {
                LOGGER.debug("Safra remote rendezvous config did not contain a valid URL for api-{}", apiVersion);
                return;
            }

            P2pConstants.setRuntimeSiteApiVersion(apiVersion);
            P2pConstants.setRuntimeRendezvousUrl(remoteUrl);
        } catch (Exception exception) {
            LOGGER.debug("Safra remote rendezvous bootstrap skipped: {}", exception.toString());
        }
    }

    private static String siteApiVersion() {
        String resolved = P2pConstants.siteApiVersion();
        return resolved == null || resolved.isBlank() ? "3.0" : resolved;
    }
}
