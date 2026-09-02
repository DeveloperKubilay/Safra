package org.developerkubilay.safra.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.server.MinecraftServer;
import org.developerkubilay.safra.p2p.CachedRendezvousConfigLoader;
import org.developerkubilay.safra.p2p.ConsoleShareCodePrinter;
import org.developerkubilay.safra.p2p.P2pHostService;
import org.developerkubilay.safra.p2p.P2pHostSupport;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.developerkubilay.safra.p2p.RemoteRendezvousBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class DedicatedP2pServerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Safra P2P");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static P2pHostService hostService;

    private DedicatedP2pServerManager() {
    }

    public static synchronized void serverStarted(MinecraftServer server) {
        if (!server.isDedicatedServer()) {
            return;
        }

        stopHosting();
        CachedRendezvousConfigLoader.initialize(Paths.get("config", "safra-client.json"));
        RemoteRendezvousBootstrap.initializeDedicated();

        int tcpPort = server.getPort();
        String fixedCode = loadOrCreateFixedCode(Paths.get("config", "safra-client.json"));
        try {
            P2pHostSupport.HostStartResult hostStartResult = P2pHostSupport.startDedicatedHost(tcpPort, server.getLocalIp(), fixedCode, LOGGER);
            hostService = hostStartResult.service();
            String shareCodeText = hostStartResult.shareCode().toDisplayCode();
            LOGGER.info("Safra P2P dedicated server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
            ConsoleShareCodePrinter.printDedicatedShareCodeIfSupported(shareCodeText);
            LOGGER.info("Players should use Direct Connect, enable P2P, and paste this code.");
            hostService.startBedrockRelay(
                address -> {
                    LOGGER.info("Safra Bedrock server started: {}", address);
                    if (!server.getPlayerList().isUsingWhitelist()) {
                        LOGGER.warn("Safra: Enable the whitelist for your security.");
                    }
                },
                () -> LOGGER.warn("Safra Bedrock relay servers are currently full")
            );
        } catch (IOException exception) {
            LOGGER.warn("Safra P2P dedicated server could not start on local TCP port {}", tcpPort, exception);
        }
    }

    public static synchronized void serverStopping(MinecraftServer server) {
        if (server.isDedicatedServer()) {
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

    private static String loadOrCreateFixedCode(Path configPath) {
        try {
            JsonObject config = readConfig(configPath);
            JsonElement enabled = config.get("openToLanFixedCodeEnabled");
            if (enabled != null) {
                if (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) {
                    throw new JsonSyntaxException("openToLanFixedCodeEnabled must be a boolean");
                }
                if (!enabled.getAsBoolean()) {
                    return null;
                }
            }

            JsonElement storedCode = config.get("openToLanFixedCode");
            if (storedCode != null && (!storedCode.isJsonPrimitive() || !storedCode.getAsJsonPrimitive().isString())) {
                throw new JsonSyntaxException("openToLanFixedCode must be a string");
            }

            String fixedCode = storedCode == null
                ? null
                : P2pShareCode.normalizeRendezvousCode(storedCode.getAsString());
            if (fixedCode != null) {
                return fixedCode;
            }

            fixedCode = P2pShareCode.createRendezvousCode(P2pShareCode.FIXED_RENDEZVOUS_CODE_LENGTH);
            config.addProperty("openToLanFixedCodeEnabled", true);
            config.addProperty("openToLanFixedCode", fixedCode);
            writeConfig(configPath, config);
            LOGGER.info("Safra dedicated share code created and stored in {}", configPath);
            return fixedCode;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Safra dedicated fixed code could not be read from {}", configPath, exception);
            return null;
        }
    }

    private static JsonObject readConfig(Path configPath) throws IOException {
        if (!Files.isRegularFile(configPath)) {
            return new JsonObject();
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new JsonSyntaxException("Safra config root must be a JSON object");
            }

            return parsed.getAsJsonObject();
        }
    }

    private static void writeConfig(Path configPath, JsonObject config) throws IOException {
        Path parent = configPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(config, writer);
        }
    }
}
