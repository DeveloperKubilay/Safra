package org.developerkubilay.safra.client.p2p;

import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
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
            ? safraError(new TranslatableText("safra.p2p.error.direct_fallback"))
            : details;
    }

    private static Text safraError(Text details) {
        return new LiteralText("Safra Error: ").append(details);
    }
}
