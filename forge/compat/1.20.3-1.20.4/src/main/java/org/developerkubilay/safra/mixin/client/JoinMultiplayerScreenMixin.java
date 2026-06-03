package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeVersionCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin extends Screen {
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger(JoinMultiplayerScreenMixin.class);

    protected JoinMultiplayerScreenMixin(net.minecraft.network.chat.Component title) {
        super(title);
    }

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerData serverData, CallbackInfo ci) {
        if (serverData == null) {
            return;
        }
        String address = ForgeVersionCompat.getServerAddress(serverData);
        if (!P2pManager.isP2pStoredAddress(address)) {
            return;
        }
        SAFRA_LOGGER.info("Safra JoinMultiplayer intercepted stored address {}", address);

        ProgressScreen progressScreen = new ProgressScreen(false);
        safra$setProgressText(progressScreen,
            ForgeComponentCompat.translatable("connect.connecting"),
            ForgeComponentCompat.translatable("safra.p2p.prepare_message"));
        Minecraft minecraft = ForgeVersionCompat.getMinecraftInstance();
        ForgeVersionCompat.setScreen(minecraft, progressScreen);
        P2pManager.getInstance()
            .createRewriteAsync(serverData)
            .orTimeout(P2pConstants.RENDEZVOUS_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
            .whenComplete((rewriteResult, throwable) ->
            minecraft.execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    SAFRA_LOGGER.warn("Safra JoinMultiplayer rewrite failed for {}: {}", address, message);
                    ForgeVersionCompat.setScreen(minecraft, new DisconnectedScreen(
                        (Screen) (Object) this,
                        ForgeComponentCompat.translatable("connect.failed"),
                        ForgeComponentCompat.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                SAFRA_LOGGER.info("Safra JoinMultiplayer rewrite complete for {} -> {}", address,
                    ForgeVersionCompat.getServerAddress(rewriteResult.serverInfo()));
                ForgeVersionCompat.startConnect((Screen) (Object) this, minecraft, rewriteResult.serverAddress(), rewriteResult.serverInfo(), false);
            })
        );
        ci.cancel();
    }

    private static void safra$setProgressText(ProgressScreen progressScreen, net.minecraft.network.chat.Component header,
                                              net.minecraft.network.chat.Component stage) {
        safra$call(progressScreen, new Class<?>[]{net.minecraft.network.chat.Component.class}, new Object[]{header}, "progressStart", "m_98822_");
        safra$call(progressScreen, new Class<?>[]{net.minecraft.network.chat.Component.class}, new Object[]{stage}, "progressStage", "m_98825_");
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
