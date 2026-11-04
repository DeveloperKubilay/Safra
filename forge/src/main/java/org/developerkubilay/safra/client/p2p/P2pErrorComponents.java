package org.developerkubilay.safra.client.p2p;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.util.text.event.ClickEvent;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.p2p.P2pErrorKind;

public final class P2pErrorComponents {
    private P2pErrorComponents() {
    }

    public static void warmUp() {
        P2pErrorKind.values();
    }

    public static ITextComponent preparationFailure(Throwable throwable) {
        P2pErrorKind kind = P2pErrorKind.classify(throwable);
        if (kind != P2pErrorKind.OTHER) {
            return safraError(new TranslationTextComponent(kind.translationKey()));
        }
        String message = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        return new TranslationTextComponent("safra.p2p.prepare_failed", message);
    }

    public static ITextComponent disconnectFailure(ITextComponent details) {
        if (details == null || details.getString().startsWith("Safra Error: ")) {
            return details;
        }
        P2pManager.ClientFailureContext context = P2pManager.getInstance().consumeClientFailureContext();
        if (!context.p2p()) {
            return details;
        }
        if (context.kind() != P2pErrorKind.OTHER) {
            return safraError(new TranslationTextComponent(context.kind().translationKey()));
        }
        P2pErrorKind kind = P2pErrorKind.classify(details.getString());
        if (kind != P2pErrorKind.OTHER) {
            return safraError(new TranslationTextComponent(kind.translationKey()));
        }
        return context.directShareAddress()
            ? safraErrorWithDiscord(new TranslationTextComponent("safra.p2p.error.direct_fallback"))
            : details;
    }

    private static ITextComponent safraError(ITextComponent details) {
        return new StringTextComponent("Safra Error: " + details.getString());
    }

    private static ITextComponent safraErrorWithDiscord(ITextComponent details) {
        String discordUrl = RemoteRendezvousConfigUpdater.discordUrl();
        ITextComponent link = new StringTextComponent(discordUrl).setStyle(new Style()
            .setColor(TextFormatting.BLUE).setUnderlined(true)
            .setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, discordUrl)));
        return new StringTextComponent("Safra Error: " + details.getString() + "\n").appendSibling(link);
    }
}
