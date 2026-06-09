package org.developerkubilay.safra.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class SafraBuildInfo {
    private static final String DEFAULT_VERSION = "unknown";
    private static final String DEFAULT_TEST_MODE = "off";
    private static final String modVersion;
    private static final String minecraftVersion;
    private static final String loaderName;
    private static final String loaderVersion;
    private static final String testMode;

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
        testMode = value(properties, "testMode", DEFAULT_TEST_MODE);
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

    public static String testMode() {
        return testMode;
    }

    private static String value(Properties properties, String key) {
        return value(properties, key, DEFAULT_VERSION);
    }

    private static String value(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value.trim();
    }
}
