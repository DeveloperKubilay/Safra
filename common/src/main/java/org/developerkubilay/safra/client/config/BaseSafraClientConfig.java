package org.developerkubilay.safra.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.P2pShareCode;
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
    protected boolean openToLanFixedCodeEnabled = true;
    protected String openToLanFixedCode = "";
    protected Map<String, String> openToLanGameRules = new LinkedHashMap<>();
    protected boolean directConnectP2pEnabled = true;
    protected boolean neverUseRelayServer = false;
    protected String rendezvousUrl = "";
    protected String siteApiVersion = "3.0";

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

    public synchronized boolean isNeverUseRelayServer() {
        return neverUseRelayServer;
    }

    public synchronized void setNeverUseRelayServer(boolean neverUseRelayServer) {
        if (this.neverUseRelayServer != neverUseRelayServer) {
            this.neverUseRelayServer = neverUseRelayServer;
            P2pConstants.setRuntimeNeverUseRelayServer(neverUseRelayServer);
            save();
        }
    }

    public synchronized String getRendezvousUrl() {
        return rendezvousUrl;
    }

    public synchronized void setRendezvousUrl(String rendezvousUrl) {
        String normalized = normalizeRendezvousUrl(rendezvousUrl);
        if (!this.rendezvousUrl.equals(normalized)) {
            this.rendezvousUrl = normalized;
            save();
        }
    }

    public synchronized String getSiteApiVersion() {
        return siteApiVersion;
    }

    public synchronized void setSiteApiVersion(String siteApiVersion) {
        String normalized = normalizeSiteApiVersion(siteApiVersion);
        if (!this.siteApiVersion.equals(normalized)) {
            this.siteApiVersion = normalized;
            P2pConstants.setRuntimeSiteApiVersion(normalized);
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

    public synchronized boolean isOpenToLanFixedCodeEnabled() {
        return openToLanFixedCodeEnabled;
    }

    public synchronized void setOpenToLanFixedCodeEnabled(boolean openToLanFixedCodeEnabled) {
        if (this.openToLanFixedCodeEnabled != openToLanFixedCodeEnabled) {
            this.openToLanFixedCodeEnabled = openToLanFixedCodeEnabled;
            save();
        }
    }

    public synchronized String getOpenToLanFixedCode() {
        return openToLanFixedCode;
    }

    public synchronized void setOpenToLanFixedCode(String openToLanFixedCode) {
        String normalized = normalizeOpenToLanFixedCode(openToLanFixedCode);
        if (!this.openToLanFixedCode.equals(normalized)) {
            this.openToLanFixedCode = normalized;
            save();
        }
    }

    public synchronized String ensureOpenToLanFixedCode() {
        String normalized = normalizeOpenToLanFixedCode(openToLanFixedCode);
        if (normalized.isBlank()) {
            normalized = P2pShareCode.createRendezvousCode(P2pShareCode.FIXED_RENDEZVOUS_CODE_LENGTH);
        }
        if (!normalized.equals(openToLanFixedCode)) {
            openToLanFixedCode = normalized;
            save();
        }
        return openToLanFixedCode;
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

    public synchronized void resetOpenToLanGameRules() {
        boolean changed = !openToLanGameRules.isEmpty();
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
        boolean changed = false;
        if (openToLanGameRules == null) {
            openToLanGameRules = new LinkedHashMap<>();
            changed = true;
        }
        String normalizedFixedCode = normalizeOpenToLanFixedCode(openToLanFixedCode);
        if (!normalizedFixedCode.equals(openToLanFixedCode)) {
            openToLanFixedCode = normalizedFixedCode;
            changed = true;
        }
        String normalizedRendezvousUrl = normalizeRendezvousUrl(rendezvousUrl);
        if (!normalizedRendezvousUrl.equals(rendezvousUrl)) {
            rendezvousUrl = normalizedRendezvousUrl;
            changed = true;
        }
        String normalizedSiteApiVersion = normalizeSiteApiVersion(siteApiVersion);
        if (!normalizedSiteApiVersion.equals(siteApiVersion)) {
            siteApiVersion = normalizedSiteApiVersion;
            changed = true;
        }

        return changed;
    }

    private static String normalizeRendezvousUrl(String rendezvousUrl) {
        return P2pConstants.isValidRendezvousUrl(rendezvousUrl) ? rendezvousUrl.trim() : "";
    }

    private static String normalizeSiteApiVersion(String siteApiVersion) {
        if (siteApiVersion == null || siteApiVersion.isBlank()) {
            return "3.0";
        }
        return "test-only".equalsIgnoreCase(siteApiVersion.trim()) ? "test-only" : "3.0";
    }

    private static String normalizeOpenToLanFixedCode(String openToLanFixedCode) {
        String normalized = P2pShareCode.normalizeRendezvousCode(openToLanFixedCode);
        return normalized == null ? "" : normalized;
    }
}
