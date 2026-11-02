package org.developerkubilay.safra.client.p2p;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.DeOpCommands;
import net.minecraft.server.commands.OpCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.StoredUserList;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class SafraWorldOpsManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafraWorldOpsManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    private static volatile ServerOpList recognizedOpsList;
    private static volatile boolean hostingActive;
    private static volatile boolean persistenceDisabled;

    private static volatile WeakReference<IntegratedServer> boundServer;
    private static Path worldOpsPath;
    private static boolean malformedWarned;
    private static long activeHostingGeneration = -1;

    private SafraWorldOpsManager() {
    }

    public static boolean isRecognizedOpsList(StoredUserList<?, ?> list) {
        return recognizedOpsList != null && list == recognizedOpsList;
    }

    public static void registerCommands(Commands commands) {
        CommandDispatcher<CommandSourceStack> vanillaCommands = new CommandDispatcher<>();
        OpCommand.register(vanillaCommands);
        DeOpCommands.register(vanillaCommands);
        for (CommandNode<CommandSourceStack> node : vanillaCommands.getRoot().getChildren()) {
            if (commands.getDispatcher().getRoot().getChild(node.getName()) == null) {
                var builder = node.createBuilder().requires(source -> {
                    WeakReference<IntegratedServer> reference = boundServer;
                    return hostingActive && reference != null && reference.get() == source.getServer()
                        && P2pManager.getInstance().isHosting() && node.canUse(source);
                });
                for (CommandNode<CommandSourceStack> child : node.getChildren()) {
                    builder.then(child);
                }
                commands.getDispatcher().getRoot().addChild(builder.build());
            }
        }
    }

    public static void activate(IntegratedServer server, long generation, boolean grantHostOperator) {
        if (server == null) {
            return;
        }

        P2pManager p2pManager = P2pManager.getInstance();
        if (!p2pManager.isHosting() || p2pManager.getHostStartGeneration() != generation) {
            return;
        }

        synchronized (LOCK) {
            ServerOpList currentOps = server.getPlayerList().getOps();
            boolean isFirstActivationForThisList = (recognizedOpsList != currentOps);
            recognizedOpsList = currentOps;
            boundServer = new WeakReference<>(server);
            activeHostingGeneration = generation;
            worldOpsPath = server.getWorldPath(LevelResource.ROOT).resolve("ops.json");
            hostingActive = true;

            if (isFirstActivationForThisList) {
                loadWorldOps(server, currentOps, worldOpsPath);
            }

            if (grantHostOperator) {
                applyHostOperator(server, currentOps);
            }

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                server.getPlayerList().sendPlayerPermissionLevel(player);
            }
        }
    }

    private static void applyHostOperator(IntegratedServer server, ServerOpList ops) {
        GameProfile hostProfile = server.getSingleplayerProfile();
        if (hostProfile == null) {
            return;
        }

        NameAndId hostNameAndId = new NameAndId(hostProfile);
        ServerOpListEntry existing = ops.get(hostNameAndId);
        if (existing == null || existing.permissions().level() != PermissionLevel.OWNERS) {
            server.getPlayerList().op(hostNameAndId, Optional.of(LevelBasedPermissionSet.OWNER), Optional.of(true));
        }
    }

    private static void loadWorldOps(IntegratedServer server, ServerOpList ops, Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }

        JsonElement rootElement;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            rootElement = JsonParser.parseReader(reader);
        } catch (Exception exception) {
            LOGGER.warn("Safra: Could not read world ops.json at {}", path, exception);
            handleMalformedFile();
            return;
        }

        if (!rootElement.isJsonArray()) {
            LOGGER.warn("Safra: World ops.json is not a JSON array at {}", path);
            handleMalformedFile();
            return;
        }

        JsonArray array = rootElement.getAsJsonArray();
        Map<String, ServerOpListEntry> diskEntries = new LinkedHashMap<>();
        try {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw new IllegalStateException("Element is not a JSON object");
                }
                JsonObject object = element.getAsJsonObject();
                ServerOpListEntry entry = new ServerOpListEntry(object);
                if (entry.getUser() != null) {
                    diskEntries.put(entry.getUser().id().toString(), entry);
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Safra: Failed to deserialize entry in world ops.json at {}", path, exception);
            handleMalformedFile();
            return;
        }

        @SuppressWarnings("unchecked")
        SafraStoredUserList<NameAndId, ServerOpListEntry> accessor = (SafraStoredUserList<NameAndId, ServerOpListEntry>) ops;
        Map<String, ServerOpListEntry> liveMap = accessor.safra$getMap();
        Map<String, ServerOpListEntry> mergedMap = new LinkedHashMap<>(diskEntries);
        mergedMap.putAll(liveMap);

        liveMap.clear();
        liveMap.putAll(mergedMap);

        handleSave(ops);
    }

    private static void handleMalformedFile() {
        persistenceDisabled = true;
        if (!malformedWarned) {
            malformedWarned = true;
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.execute(() -> {
                    if (client.gui != null && client.gui.getChat() != null) {
                        client.gui.getChat().addClientSystemMessage(
                            Component.translatable("safra.p2p.warn.malformed_ops").withStyle(ChatFormatting.YELLOW)
                        );
                    }
                });
            }
        }
    }

    public static void handleSave(StoredUserList<?, ?> list) {
        synchronized (LOCK) {
            if (!hostingActive || persistenceDisabled || worldOpsPath == null) {
                return;
            }
            if (list != recognizedOpsList) {
                return;
            }

            Path tempPath = null;
            try {
                @SuppressWarnings("unchecked")
                SafraStoredUserList<NameAndId, ServerOpListEntry> accessor = (SafraStoredUserList<NameAndId, ServerOpListEntry>) list;
                Map<String, ServerOpListEntry> map = accessor.safra$getMap();

                JsonArray result = new JsonArray();
                for (ServerOpListEntry entry : map.values()) {
                    if (entry != null && entry.getUser() != null) {
                        JsonObject object = new JsonObject();
                        ((SafraStoredUserEntry) entry).safra$serialize(object);
                        result.add(object);
                    }
                }

                Path parentDir = worldOpsPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }

                tempPath = worldOpsPath.resolveSibling(worldOpsPath.getFileName().toString() + ".tmp");
                try (BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                    GSON.toJson(result, writer);
                }

                try {
                    Files.move(tempPath, worldOpsPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(tempPath, worldOpsPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception exception) {
                LOGGER.warn("Safra: Failed to save world ops.json to {}", worldOpsPath, exception);
                if (tempPath != null) {
                    try {
                        Files.deleteIfExists(tempPath);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    public static void handleLoad(StoredUserList<?, ?> list) {
        synchronized (LOCK) {
            if (!hostingActive || persistenceDisabled || worldOpsPath == null) {
                return;
            }
            if (list != recognizedOpsList) {
                return;
            }

            IntegratedServer server = boundServer != null ? boundServer.get() : null;
            if (server != null) {
                loadWorldOps(server, recognizedOpsList, worldOpsPath);
            }
        }
    }

    public static void onHostingStopped() {
        synchronized (LOCK) {
            hostingActive = false;
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            recognizedOpsList = null;
            boundServer = null;
            worldOpsPath = null;
            hostingActive = false;
            persistenceDisabled = false;
            malformedWarned = false;
            activeHostingGeneration = -1;
        }
    }
}
