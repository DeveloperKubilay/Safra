package org.developerkubilay.safra.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class DedicatedServerPropertyFixer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DedicatedServerPropertyFixer.class);

    private DedicatedServerPropertyFixer() {
    }

    static void ensureOfflineSafeDefaults(Path serverPropertiesPath) {
        if (serverPropertiesPath == null || !Files.isRegularFile(serverPropertiesPath)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(serverPropertiesPath)) {
            properties.load(inputStream);
        } catch (IOException exception) {
            LOGGER.warn("Safra could not read server.properties for dedicated auth normalization", exception);
            return;
        }

        boolean changed = false;
        changed |= setIfDifferent(properties, "online-mode", "false");
        changed |= setIfDifferent(properties, "enforce-secure-profile", "false");
        changed |= setIfDifferent(properties, "prevent-proxy-connections", "false");
        if (!changed) {
            return;
        }

        try (OutputStream outputStream = Files.newOutputStream(serverPropertiesPath)) {
            properties.store(outputStream, "Minecraft server properties");
        } catch (IOException exception) {
            LOGGER.warn("Safra could not update server.properties for dedicated auth normalization", exception);
        }
    }

    private static boolean setIfDifferent(Properties properties, String key, String expectedValue) {
        String currentValue = properties.getProperty(key);
        if (expectedValue.equals(currentValue)) {
            return false;
        }
        properties.setProperty(key, expectedValue);
        return true;
    }
}
