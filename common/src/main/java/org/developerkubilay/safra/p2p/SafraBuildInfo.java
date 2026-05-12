package org.developerkubilay.safra.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class SafraBuildInfo {
    private static final String DEFAULT_VERSION = "unknown";
    private static final String modVersion;
    private static final String minecraftVersion;

    static {
        Properties properties = new Properties();
        try (InputStream inputStream = SafraBuildInfo.class.getClassLoader().getResourceAsStream("safra-build.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException ignored) {
        }

        modVersion = value(properties, "modVersion");
        minecraftVersion = value(properties, "minecraftVersion");
    }

    private SafraBuildInfo() {
    }

    static String modVersion() {
        return modVersion;
    }

    static String minecraftVersion() {
        return minecraftVersion;
    }

    private static String value(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return DEFAULT_VERSION;
        }

        return value.trim();
    }
}
