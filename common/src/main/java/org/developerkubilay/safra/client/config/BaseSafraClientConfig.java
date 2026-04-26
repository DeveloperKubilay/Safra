package org.developerkubilay.safra.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public abstract class BaseSafraClientConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaseSafraClientConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    protected boolean openToLanP2pEnabled = true;
    protected boolean openToLanOnlineModeEnabled = false;
    protected boolean openToLanAllowCommandsEnabled = false;
    protected Map<String, String> openToLanGameRules = new LinkedHashMap<>();
    protected boolean directConnectP2pEnabled = true;

    protected abstract Path configPath();

    public synchronized boolean isOpenToLanP2pEnabled() {
        return openToLanP2pEnabled;
    }

    public synchronized void setOpenToLanP2pEnabled(boolean openToLanP2pEnabled) {
        if (this.openToLanP2pEnabled != openToLanP2pEnabled) {
            this.openToLanP2pEnabled = openToLanP2pEnabled;
            save();
        }
    }

    public synchronized boolean isOpenToLanOnlineModeEnabled() {
        return openToLanOnlineModeEnabled;
    }

    public synchronized void setOpenToLanOnlineModeEnabled(boolean openToLanOnlineModeEnabled) {
        if (this.openToLanOnlineModeEnabled != openToLanOnlineModeEnabled) {
            this.openToLanOnlineModeEnabled = openToLanOnlineModeEnabled;
            save();
        }
    }

    public synchronized boolean isDirectConnectP2pEnabled() {
        return directConnectP2pEnabled;
    }

    public synchronized void setDirectConnectP2pEnabled(boolean directConnectP2pEnabled) {
        if (this.directConnectP2pEnabled != directConnectP2pEnabled) {
            this.directConnectP2pEnabled = directConnectP2pEnabled;
            save();
        }
    }

    public synchronized boolean isOpenToLanAllowCommandsEnabled() {
        return openToLanAllowCommandsEnabled;
    }

    public synchronized void setOpenToLanAllowCommandsEnabled(boolean openToLanAllowCommandsEnabled) {
        if (this.openToLanAllowCommandsEnabled != openToLanAllowCommandsEnabled) {
            this.openToLanAllowCommandsEnabled = openToLanAllowCommandsEnabled;
            save();
        }
    }

    public synchronized Map<String, String> getOpenToLanGameRules() {
        return new LinkedHashMap<>(openToLanGameRules);
    }

    public synchronized void setOpenToLanGameRules(Map<String, String> openToLanGameRules) {
        Map<String, String> normalized = openToLanGameRules == null || openToLanGameRules.isEmpty()
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(openToLanGameRules);
        if (!this.openToLanGameRules.equals(normalized)) {
            this.openToLanGameRules = normalized;
            save();
        }
    }

    public synchronized void resetOpenToLanServerSettings() {
        boolean changed = openToLanAllowCommandsEnabled || !openToLanGameRules.isEmpty();
        openToLanAllowCommandsEnabled = false;
        openToLanGameRules = new LinkedHashMap<>();
        if (changed) {
            save();
        }
    }

    protected synchronized void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            LOGGER.warn("Safra client config could not be saved", exception);
        }
    }

    protected static <T extends BaseSafraClientConfig> T load(T fallback) {
        Path path = fallback.configPath();
        if (!Files.exists(path)) {
            fallback.save();
            return fallback;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            @SuppressWarnings("unchecked")
            T config = (T) GSON.fromJson(reader, fallback.getClass());
            T resolvedConfig = config == null ? fallback : config;
            boolean changed = resolvedConfig.normalize();
            if (changed) {
                resolvedConfig.save();
            }
            return resolvedConfig;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Safra client config could not be read, using defaults", exception);
            return fallback;
        }
    }

    final boolean normalize() {
        if (openToLanGameRules == null) {
            openToLanGameRules = new LinkedHashMap<>();
            return true;
        }
        return false;
    }
}
