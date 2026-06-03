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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(net.minecraft.network.chat.Component title) {
        super(title);
    }

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerData serverData, CallbackInfo ci) {
        if (serverData == null || !P2pManager.isLikelyP2pAddress(ForgeVersionCompat.getServerAddress(serverData))) {
            return;
        }

        Minecraft client = safra$getClientInstance();
        if (client == null) {
            return;
        }

        ProgressScreen progressScreen = new ProgressScreen(false);
        safra$setProgressText(progressScreen,
            ForgeComponentCompat.translatable("connect.connecting"),
            ForgeComponentCompat.translatable("safra.p2p.prepare_message"));
        ForgeVersionCompat.setScreen(client, progressScreen);
        P2pManager.getInstance().createRewriteAsync(serverData).whenComplete((rewriteResult, throwable) ->
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
                        (Screen) (Object) this,
                        ForgeComponentCompat.translatable("connect.failed"),
                        ForgeComponentCompat.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                ForgeVersionCompat.startConnect((Screen) (Object) this, client, rewriteResult.serverAddress(), rewriteResult.serverInfo(), false);
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

    private static Minecraft safra$getClientInstance() {
        Object value = safra$call(Minecraft.class, new Class<?>[0], new Object[0], "getInstance", "m_91087_");
        return value instanceof Minecraft client ? client : null;
    }
}
