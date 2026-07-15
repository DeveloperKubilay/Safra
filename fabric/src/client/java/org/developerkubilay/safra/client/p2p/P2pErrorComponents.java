package org.developerkubilay.safra.client.p2p;

import net.minecraft.text.Text;
import org.developerkubilay.safra.p2p.P2pErrorKind;

public final class P2pErrorComponents {
    private P2pErrorComponents() {
    }

    public static Text preparationFailure(Throwable throwable) {
        P2pErrorKind kind = P2pErrorKind.classify(throwable);
        if (kind != P2pErrorKind.OTHER) {
            return safraError(FabricClientCompat.translatable(kind.translationKey()));
        }
        String message = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        return FabricClientCompat.translatable("safra.p2p.prepare_failed", message);
    }

    public static Text disconnectDetails(Text details) {
        P2pManager manager = P2pManager.getInstance();
        P2pManager.ClientFailureContext context = manager.consumeClientFailureContext();
        if (!context.p2p()) {
            return details;
        }
        P2pErrorKind kind = P2pErrorKind.classify(details.getString() + " " + details);
        if (kind != P2pErrorKind.OTHER) {
            return safraError(FabricClientCompat.translatable(kind.translationKey()));
        }
        if (context.directShareAddress()) {
            return safraError(FabricClientCompat.translatable("safra.p2p.error.direct_fallback"));
        }
        return details;
    }

    private static Text safraError(Text details) {
        return FabricClientCompat.literal("Safra Error: ").append(details);
    }
}
