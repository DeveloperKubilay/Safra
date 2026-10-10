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
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private RemoteRendezvousBootstrap() {
    }

    public static void initializeDedicated() {
        if (P2pConstants.hasExplicitRendezvousUrlOverride()) {
            return;
        }

        P2pConstants.applyDefaultRendezvousUrlIfAbsent();

        for (String url : P2pConstants.REMOTE_CONFIG_URLS) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", SafraBuildInfo.userAgent())
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    LOGGER.debug("Safra remote rendezvous config request to {} returned HTTP {}", url, response.statusCode());
                    continue;
                }

                String body = response.body();
                if (body == null || body.isBlank()) {
                    continue;
                }

                String apiVersion = siteApiVersion();
                String remoteUrl = RemoteRendezvousConfigParser.parseRemoteUrl(body, apiVersion, "dedicated");
                if (!P2pConstants.isValidRendezvousUrl(remoteUrl)) {
                    LOGGER.debug("Safra remote rendezvous config from {} did not contain a valid URL for api-{}", url, apiVersion);
                    continue;
                }

                P2pConstants.setRuntimeSiteApiVersion(apiVersion);
                P2pConstants.setRuntimeRendezvousUrl(remoteUrl);
                return;
            } catch (Exception exception) {
                LOGGER.debug("Safra remote rendezvous bootstrap from {} skipped: {}", url, exception.toString());
            }
        }
    }

    private static String siteApiVersion() {
        String resolved = P2pConstants.siteApiVersion();
        return resolved == null || resolved.isBlank() ? "3.0" : resolved;
    }
}
