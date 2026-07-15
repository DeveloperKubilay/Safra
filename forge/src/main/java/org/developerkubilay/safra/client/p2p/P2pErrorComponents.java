package org.developerkubilay.safra.client.p2p;

import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.ForgeClientCompat;
import org.developerkubilay.safra.p2p.P2pErrorKind;

public final class P2pErrorComponents {
    private P2pErrorComponents() {
    }

    public static Component preparationFailure(Throwable throwable) {
        P2pErrorKind kind = P2pErrorKind.classify(throwable);
        if (kind != P2pErrorKind.OTHER) {
            return safraError(ForgeClientCompat.translatable(kind.translationKey()));
        }
        String message = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        return ForgeClientCompat.translatable("safra.p2p.prepare_failed", message);
    }

    public static Component disconnectDetails(Component details) {
        P2pManager manager = P2pManager.getInstance();
        P2pManager.ClientFailureContext context = manager.consumeClientFailureContext();
        if (!context.p2p()) {
            return details;
        }
        P2pErrorKind kind = P2pErrorKind.classify(details.getString() + " " + details);
        if (kind != P2pErrorKind.OTHER) {
            return safraError(ForgeClientCompat.translatable(kind.translationKey()));
        }
        if (context.directShareAddress()) {
            return safraError(ForgeClientCompat.translatable("safra.p2p.error.direct_fallback"));
        }
        return details;
    }

    private static Component safraError(Component details) {
        return ForgeClientCompat.append(ForgeClientCompat.literal("Safra Error: "), details);
    }
}
