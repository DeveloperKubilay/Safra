package org.developerkubilay.safra.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeLanGameRules;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(IntegratedServer.class)
abstract class IntegratedServerMixin {
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");

    @Inject(method = "publishServer", at = @At("HEAD"))
    private void safra$applyOnlineMode(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        IntegratedServer server = (IntegratedServer) (Object) this;
        safra$callBooleanSetter(server, ForgeLanSessionState.isOnlineModeEnabled(), "setUsesAuthentication", "m_129985_", "m_11004_");
        if (ForgeLanSessionState.isP2pEnabled()) {
            safra$callBooleanSetter(server, false, "setPreventProxyConnections", "m_295794_");
        }
        SAFRA_LOGGER.debug(
            "Safra LAN auth settings: onlineMode={}, preventProxyConnections={}",
            safra$callBooleanGetter(server, "usesAuthentication", "m_129799_"),
            safra$callBooleanGetter(server, "getPreventProxyConnections", "m_129798_")
        );
    }

    @Inject(method = "publishServer", at = @At("RETURN"))
    private void safra$startP2pHost(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        if (!ForgeLanSessionState.isP2pEnabled()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        IntegratedServer server = (IntegratedServer) (Object) this;
        ForgeLanGameRules.applyToServer(server, ForgeLanSessionState.getGameRuleSnapshot());
        int tcpPort = port;
        Minecraft client = safra$getClientInstance();
        if (client == null) {
            return;
        }
        safra$pushClientMessage(client, ForgeComponentCompat.translatable("safra.p2p.host.starting"));
        String fixedCode = ForgeLanSessionState.isFixedCodeEnabled() ? ForgeLanSessionState.getFixedCode() : null;
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode).whenComplete((shareCode, throwable) -> {
            client.execute(() -> {
                if (throwable != null) {
                    safra$publishStartFailure(client, tcpPort, throwable);
                    return;
                }

                safra$publishShareCode(client, tcpPort, shareCode);
            });
        });
    }

    private static void safra$publishShareCode(Minecraft client, int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
        safra$copyToClipboard(client, shareCodeText);

        Component shareText = ForgeComponentCompat.copyableLiteral(shareCodeText, "safra.p2p.copy_hint");
        safra$pushClientMessage(client, ForgeComponentCompat.translatable("safra.p2p.host.started", shareText));
        safra$pushClientMessage(client, ForgeComponentCompat.translatable("safra.p2p.host.copied"));
        safra$pushClientMessage(client, ForgeComponentCompat.translatable("safra.p2p.host.instructions"));
    }

    private static void safra$publishStartFailure(Minecraft client, int tcpPort, Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
        if (cause instanceof CancellationException) {
            return;
        }

        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        SAFRA_LOGGER.warn("Safra P2P could not start on local TCP port {}", tcpPort, cause);
        safra$pushClientMessage(client, ForgeComponentCompat.style(ForgeComponentCompat.translatable("safra.p2p.host.failed", message), ChatFormatting.RED));
    }

    private static void safra$callBooleanSetter(Object target, boolean value, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name, boolean.class);
                method.setAccessible(true);
                method.invoke(target, value);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static boolean safra$callBooleanGetter(Object target, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                Object result = method.invoke(target);
                if (result instanceof Boolean value) {
                    return value;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }

    private static Minecraft safra$getClientInstance() {
        Object value = safra$call(Minecraft.class, new Class<?>[0], new Object[0], "getInstance", "m_91087_");
        return value instanceof Minecraft client ? client : null;
    }

    private static void safra$copyToClipboard(Minecraft client, String text) {
        Object keyboard = safra$getField(client, "keyboard", "keyboardHandler", "f_90867_", "f_91068_");
        if (keyboard != null) {
            safra$invoke(keyboard, new Class<?>[]{String.class}, new Object[]{text}, "setClipboard", "m_90911_");
            for (Method method : keyboard.getClass().getMethods()) {
                if (method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class
                    && method.getName().toLowerCase().contains("clipboard")) {
                    try {
                        method.setAccessible(true);
                        method.invoke(keyboard, text);
                        break;
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
        }

        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Throwable ignored) {
        }
    }

    private static void safra$pushClientMessage(Minecraft client, Component message) {
        Object player = safra$getField(client, "player", "f_91074_");
        if (player == null) {
            return;
        }

        if (safra$invoke(player, new Class<?>[]{Component.class, boolean.class}, new Object[]{message, false}, "displayClientMessage", "m_5661_")) {
            return;
        }
        safra$invoke(player, new Class<?>[]{Component.class}, new Object[]{message}, "sendSystemMessage", "m_213846_");
    }

    private static Object safra$getField(Object target, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object safra$call(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
        Object instance = target instanceof Class<?> ? null : target;
        for (String name : names) {
            try {
                Method method = type.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method.invoke(instance, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static boolean safra$invoke(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
        Object instance = target instanceof Class<?> ? null : target;
        for (String name : names) {
            try {
                Method method = type.getMethod(name, parameterTypes);
                method.setAccessible(true);
                method.invoke(instance, args);
                return true;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }
}
