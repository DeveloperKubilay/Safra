package org.developerkubilay.safra.client.p2p;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.p2p.P2pErrorKind;

public final class P2pErrorComponents {
    private P2pErrorComponents() {
    }

    public static Component preparationFailure(Throwable throwable) {
        P2pErrorKind kind = P2pErrorKind.classify(throwable);
        if (kind != P2pErrorKind.OTHER) {
            return safraError(Component.translatable(kind.translationKey()));
        }
        String message = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        return Component.translatable("safra.p2p.prepare_failed", message);
    }

    public static Component disconnectDetails(Component details) {
        P2pManager manager = P2pManager.getInstance();
        P2pManager.ClientFailureContext context = manager.consumeClientFailureContext();
        if (!context.p2p()) {
            return details;
        }
        P2pErrorKind kind = P2pErrorKind.classify(details.getString() + " " + details);
        if (kind != P2pErrorKind.OTHER) {
            return safraError(Component.translatable(kind.translationKey()));
        }
        if (context.directShareAddress()) {
            return safraError(Component.translatable("safra.p2p.error.direct_fallback").append(safra$discordLink()));
        }
        return details;
    }

    private static Component safraError(Component details) {
        return Component.literal("Safra Error: ").append(details);
    }

    private static Component safra$discordLink() {
        String url = RemoteRendezvousConfigUpdater.discordUrl();
        return Component.literal("\n" + url)
            .withStyle(net.minecraft.ChatFormatting.BLUE, net.minecraft.ChatFormatting.UNDERLINE)
            .withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url))));
    }
}
