package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeVersionCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Inject(method = {"startConnecting", "m_278792_"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void safra$rewriteP2pConnection(Screen parent, Minecraft client, ServerAddress serverAddress,
                                                   ServerData serverInfo, boolean quickPlay, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isLikelyP2pAddress(ForgeVersionCompat.getServerAddress(serverInfo))) {
            return;
        }

        ProgressScreen progressScreen = new ProgressScreen(false);
        safra$setProgressText(progressScreen,
            ForgeComponentCompat.translatable("connect.connecting"),
            ForgeComponentCompat.translatable("safra.p2p.prepare_message"));
        ForgeVersionCompat.setScreen(client, progressScreen);
        P2pManager.getInstance().createRewriteAsync(serverInfo).whenComplete((rewriteResult, throwable) ->
            client.execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    ForgeVersionCompat.setScreen(client, new DisconnectedScreen(
                        parent,
                        ForgeComponentCompat.translatable("connect.failed"),
                        ForgeComponentCompat.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                CompletableFuture.delayedExecutor(75L, TimeUnit.MILLISECONDS).execute(() ->
                    client.execute(() ->
                        ForgeVersionCompat.startConnect(parent, client, rewriteResult.serverAddress(), rewriteResult.serverInfo(), quickPlay)
                    )
                );
            })
        );
        ci.cancel();
    }

    private static void safra$setProgressText(ProgressScreen progressScreen, net.minecraft.network.chat.Component header,
                                              net.minecraft.network.chat.Component stage) {
        safra$call(progressScreen, new Class<?>[]{net.minecraft.network.chat.Component.class}, new Object[]{header}, "progressStart", "m_6308_");
        safra$call(progressScreen, new Class<?>[]{net.minecraft.network.chat.Component.class}, new Object[]{stage}, "progressStage", "m_6307_");
    }

    private static Object safra$call(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        for (String name : names) {
            try {
                java.lang.reflect.Method method = target.getClass().getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
