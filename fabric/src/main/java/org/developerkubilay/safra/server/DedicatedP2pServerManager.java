package org.developerkubilay.safra.server;

import net.minecraft.server.MinecraftServer;
import org.developerkubilay.safra.p2p.CachedRendezvousConfigLoader;
import org.developerkubilay.safra.p2p.ConsoleShareCodePrinter;
import org.developerkubilay.safra.p2p.P2pHostService;
import org.developerkubilay.safra.p2p.P2pHostSupport;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.developerkubilay.safra.p2p.RemoteRendezvousBootstrap;
import org.developerkubilay.safra.p2p.SafraBuildInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DedicatedP2pServerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Safra P2P");
    private static final Pattern FIXED_CODE_ENABLED_PATTERN = Pattern.compile("\"openToLanFixedCodeEnabled\"\\s*:\\s*(true|false)");
    private static final Pattern FIXED_CODE_PATTERN = Pattern.compile("\"openToLanFixedCode\"\\s*:\\s*\"([^\"]*)\"");
    private static P2pHostService hostService;

    private DedicatedP2pServerManager() {
    }

    public static synchronized void serverStarted(MinecraftServer server) {
        if (!server.isDedicated()) {
            return;
        }

        stopHosting();
        CachedRendezvousConfigLoader.initialize(Paths.get("config", "safra-client.json"));
        RemoteRendezvousBootstrap.initializeDedicated();

        int tcpPort = server.getServerPort();
        String fixedCode = loadFixedCode(Paths.get("config", "safra-client.json"));
        try {
            P2pHostSupport.HostStartResult hostStartResult = P2pHostSupport.startDedicatedHost(
                tcpPort,
                server.getServerIp(),
                fixedCode,
                LOGGER
            );
            hostService = hostStartResult.service();
            String shareCodeText = hostStartResult.shareCode().toDisplayCode();
            LOGGER.info("Safra P2P dedicated server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
            ConsoleShareCodePrinter.printDedicatedShareCodeIfSupported(shareCodeText);
            LOGGER.info("Players should use Direct Connect, enable P2P, and paste this code.");
            if (SafraBuildInfo.minecraftVersion().startsWith("1.19")) {
                hostService.startBedrockRelay(
                    address -> {
                        LOGGER.info("Safra Bedrock server started: {}", address);
                        if (!server.getPlayerManager().isWhitelistEnabled()) {
                            LOGGER.warn("Safra: Enable the whitelist for your security.");
                        }
                    },
                    () -> LOGGER.warn("Safra Bedrock relay servers are currently full")
                );
            }
        } catch (IOException exception) {
            LOGGER.warn("Safra P2P dedicated server could not start on local TCP port {}", tcpPort, exception);
        }
    }

    public static synchronized void serverStopping(MinecraftServer server) {
        if (server.isDedicated()) {
            stopHosting();
        }
    }

    private static void stopHosting() {
        if (hostService == null) {
            return;
        }

        LOGGER.info("Safra P2P dedicated server host stopping");
        hostService.close();
        hostService = null;
    }

    private static String loadFixedCode(Path configPath) {
        try {
            if (!Files.exists(configPath)) {
                return null;
            }

            String json = Files.readString(configPath);
            Matcher enabledMatcher = FIXED_CODE_ENABLED_PATTERN.matcher(json);
            if (!enabledMatcher.find() || !Boolean.parseBoolean(enabledMatcher.group(1))) {
                return null;
            }

            Matcher codeMatcher = FIXED_CODE_PATTERN.matcher(json);
            if (!codeMatcher.find()) {
                return null;
            }

            return P2pShareCode.normalizeRendezvousCode(codeMatcher.group(1));
        } catch (IOException exception) {
            LOGGER.warn("Safra dedicated fixed code could not be read from {}", configPath, exception);
            return null;
        }
    }
}
