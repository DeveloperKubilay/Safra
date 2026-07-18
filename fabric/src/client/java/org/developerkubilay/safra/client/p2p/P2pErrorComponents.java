package org.developerkubilay.safra.client.p2p;

import net.minecraft.text.LiteralText;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.p2p.P2pErrorKind;

public final class P2pErrorComponents {
    private P2pErrorComponents() {
    }

    public static Text preparationFailure(Throwable throwable) {
        P2pErrorKind kind = P2pErrorKind.classify(throwable);
        if (kind != P2pErrorKind.OTHER) {
            return safraError(new TranslatableText(kind.translationKey()));
        }
        String message = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        return new TranslatableText("safra.p2p.prepare_failed", message);
    }

    public static Text disconnectFailure(Text details) {
        P2pManager.ClientFailureContext context = P2pManager.getInstance().consumeClientFailureContext();
        if (!context.p2p()) {
            return details;
        }
        P2pErrorKind kind = P2pErrorKind.classify(details.getString());
        if (kind != P2pErrorKind.OTHER) {
            return safraError(new TranslatableText(kind.translationKey()));
        }
        return context.directShareAddress()
            ? safraErrorWithDiscord(new TranslatableText("safra.p2p.error.direct_fallback"))
            : details;
    }

    private static Text safraError(Text details) {
        return new LiteralText("Safra Error: ").append(details);
    }

    private static Text safraErrorWithDiscord(Text details) {
        String discordUrl = RemoteRendezvousConfigUpdater.discordUrl();
        return new LiteralText("Safra Error: ")
            .append(details)
            .append(new LiteralText("\n" + discordUrl).setStyle(Style.EMPTY
                .withFormatting(Formatting.BLUE, Formatting.UNDERLINE)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, discordUrl))
            ));
    }
}
