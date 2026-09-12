package org.developerkubilay.safra.p2p;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CachedRendezvousConfigLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(CachedRendezvousConfigLoader.class);

    private CachedRendezvousConfigLoader() {
    }

    public static void initialize(Path configFile) {
        if (configFile == null || P2pConstants.hasRendezvousUrl() || !Files.isRegularFile(configFile)) {
            return;
        }

        try {
            String body = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return;
            }

            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("rendezvousUrl") || json.get("rendezvousUrl").isJsonNull()) {
                return;
            }

            String cachedUrl = json.get("rendezvousUrl").getAsString();
            if (!P2pConstants.isValidRendezvousUrl(cachedUrl)) {
                return;
            }

            P2pConstants.setRuntimeRendezvousUrl(cachedUrl);
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("Safra cached rendezvous config could not be loaded: {}", exception.toString());
        }
    }
}