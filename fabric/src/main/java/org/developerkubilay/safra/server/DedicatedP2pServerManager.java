package org.developerkubilay.safra.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

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
        JsonObject config;
        String fixedCode;
        try {
            config = readConfig(configPath);
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

            fixedCode = storedCode == null
                ? null
                : P2pShareCode.normalizeRendezvousCode(storedCode.getAsString());
            if (fixedCode != null) {
                return fixedCode;
            }
        } catch (IOException | JsonParseException exception) {
            LOGGER.warn("Safra dedicated fixed code could not be read from {}", configPath, exception);
            return null;
        }

        fixedCode = P2pShareCode.createRendezvousCode(P2pShareCode.FIXED_RENDEZVOUS_CODE_LENGTH);
        config.addProperty("openToLanFixedCodeEnabled", true);
        config.addProperty("openToLanFixedCode", fixedCode);
        try {
            writeConfig(configPath, config);
        } catch (IOException | JsonIOException exception) {
            LOGGER.warn("Safra dedicated fixed code could not be stored in {}", configPath, exception);
            return null;
        }

        LOGGER.info("Safra dedicated share code created and stored in {}", configPath);
        return fixedCode;
    }

    private static JsonObject readConfig(Path configPath) throws IOException {
        if (!Files.isRegularFile(configPath)) {
            return new JsonObject();
        }

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new JsonSyntaxException("Safra config root must be a JSON object");
            }

            return parsed.getAsJsonObject();
        }
    }

    private static void writeConfig(Path configPath, JsonObject config) throws IOException {
        Path target = resolveTarget(configPath.toAbsolutePath());
        Path parent = target.getParent();
        Files.createDirectories(parent);

        Path temporaryPath = Files.createTempFile(parent, "safra-client", ".json.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporaryPath, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }

            copyPermissions(target, temporaryPath);

            try {
                Files.move(temporaryPath, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException suppressed) {
                exception.addSuppressed(suppressed);
            }
            throw exception;
        }
    }

    /**
     * Resolves the config a symlink points at, so the write lands where the operator aimed it.
     *
     * <p>Replacing a path atomically means replacing whatever sits at that path, and if that is a
     * symlink the move destroys the link and leaves a regular file in its place. Someone who pointed
     * config/safra-client.json at a shared or version-controlled location would find the link gone
     * and their real config untouched the first time a share code was stored. Following the link
     * instead keeps that setup working, and it also keeps the temporary file on the same filesystem
     * as the file it replaces, which is what lets the move stay atomic.
     *
     * <p>Links are followed explicitly before {@link Path#toRealPath()} is used. That matters for a
     * dangling/new symlink: {@code toRealPath()} cannot resolve it because the destination does not
     * exist yet, but {@link Files#readSymbolicLink(Path)} can still tell us where the operator wants
     * the config written. A short depth limit also makes a symlink cycle fail safely instead of ever
     * falling back to replacing one of the links.
     */
    private static Path resolveTarget(Path configPath) throws IOException {
        Path target = configPath.toAbsolutePath().normalize();
        for (int depth = 0; depth < 40 && Files.isSymbolicLink(target); depth++) {
            Path linkTarget = Files.readSymbolicLink(target);
            target = linkTarget.isAbsolute()
                ? linkTarget.normalize()
                : target.getParent().resolve(linkTarget).normalize();
        }

        if (Files.isSymbolicLink(target)) {
            throw new IOException("Too many symbolic links while resolving Safra config: " + configPath);
        }

        try {
            return target.toRealPath();
        } catch (IOException exception) {
            return target;
        }
    }

    /**
     * Carries the config's current permissions onto the replacement.
     *
     * <p>{@link Files#createTempFile} creates an owner-only file, and replacing the config with it
     * hands those permissions to the config too. An operator who had deliberately widened the file -
     * a shared admin group on a dedicated box is the usual reason - would silently lose that on the
     * first write, which is not something a share-code update should decide. A config that does not
     * exist yet keeps the restrictive default: nobody has expressed an opinion about it, and that is
     * the safer of the two ways to guess.
     */
    private static void copyPermissions(Path target, Path temporaryPath) {
        if (!Files.isRegularFile(target)) {
            return;
        }

        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
            Files.setPosixFilePermissions(temporaryPath, permissions);
        } catch (UnsupportedOperationException exception) {
            // Windows and any other non-POSIX filesystem: there is nothing to carry, and the
            // replacement inherits the directory's ACL the same way the original did.
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Safra could not carry the permissions of {} onto its replacement", target, exception);
        }
    }
}
