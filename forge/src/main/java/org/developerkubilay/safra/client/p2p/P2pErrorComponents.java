package org.developerkubilay.safra.client.p2p;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.p2p.P2pErrorKind;

public final class P2pErrorComponents {
    private P2pErrorComponents() {
    }

    /**
     * Forge 1.16's ModLauncher can close the mod jar before an async network
     * callback first needs this class. Touch it during client bootstrap.
     */
    public static void warmUp() {
        // Intentionally empty.
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
        P2pManager.ClientFailureContext context = P2pManager.getInstance().consumeClientFailureContext();
        if (!context.p2p()) {
            return details;
        }
        P2pErrorKind kind = P2pErrorKind.classify(details.getString());
        if (kind != P2pErrorKind.OTHER) {
            return safraError(new TranslationTextComponent(kind.translationKey()));
        }
        return context.directShareAddress()
            ? safraError(new TranslationTextComponent("safra.p2p.error.direct_fallback"))
            : details;
    }

    private static ITextComponent safraError(ITextComponent details) {
        return new StringTextComponent("Safra Error: " + details.getString());
    }
}
