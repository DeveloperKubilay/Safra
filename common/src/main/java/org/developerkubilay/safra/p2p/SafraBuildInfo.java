package org.developerkubilay.safra.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class SafraBuildInfo {
    private static final String DEFAULT_VERSION = "unknown";
    private static final String modVersion;
    private static final String minecraftVersion;
    private static final String loaderName;
    private static final String loaderVersion;

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
        loaderName = value(properties, "loaderName");
        loaderVersion = value(properties, "loaderVersion");
    }

    private SafraBuildInfo() {
    }

    public static String modVersion() {
        return modVersion;
    }

    public static String minecraftVersion() {
        return minecraftVersion;
    }

    public static String loaderName() {
        return loaderName;
    }

    public static String loaderVersion() {
        return loaderVersion;
    }

    private static String value(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (P2pText.isBlank(value)) {
            return DEFAULT_VERSION;
        }

        return value.trim();
    }
}
