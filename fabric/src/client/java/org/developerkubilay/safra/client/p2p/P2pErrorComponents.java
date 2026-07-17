package org.developerkubilay.safra.client.p2p;

import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.p2p.P2pErrorKind;

public final class P2pErrorComponents {
    private P2pErrorComponents() {
    }

    public static Text preparationFailure(Throwable throwable) {
        P2pErrorKind kind = P2pErrorKind.classify(throwable);
        if (kind != P2pErrorKind.OTHER) {
            return safraError(Text.translatable(kind.translationKey()));
        }
        String message = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        return Text.translatable("safra.p2p.prepare_failed", message);
    }

    public static Text disconnectDetails(Text details) {
        P2pManager.ClientFailureContext context = P2pManager.getInstance().consumeClientFailureContext();
        if (!context.p2p()) {
            return details;
        }
        P2pErrorKind kind = P2pErrorKind.classify(details.getString());
        if (kind != P2pErrorKind.OTHER) {
            return safraError(Text.translatable(kind.translationKey()));
        }
        if (context.directShareAddress()) {
            return safraError(Text.translatable("safra.p2p.error.direct_fallback").append(safra$discordLink()));
        }
        return details;
    }

    private static Text safraError(Text details) {
        return Text.literal("Safra Error: ").append(details);
    }

    private static Text safra$discordLink() {
        String url = RemoteRendezvousConfigUpdater.discordUrl();
        return Text.literal("\n" + url).setStyle(Style.EMPTY
            .withColor(Formatting.BLUE)
            .withUnderline(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
        );
    }
}
