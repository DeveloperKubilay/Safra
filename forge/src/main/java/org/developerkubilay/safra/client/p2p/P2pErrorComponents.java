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

    /**
     * Forge 1.16's ModLauncher can close the mod jar before an async network
     * callback first needs this class. Touch it during client bootstrap.
     */
    public static void warmUp() {
        // Also load the enum used by the deferred error callback. Forge 1.16's
        // ModLauncher may no longer be able to read the mod jar at that point.
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
        P2pManager.ClientFailureContext context = P2pManager.getInstance().consumeClientFailureContext();
        if (!context.p2p()) {
            return details;
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
        return safra$append(
            new StringTextComponent("Safra Error: " + details.getString() + "\n"),
            safra$clickableLink(discordUrl)
        );
    }

    private static ITextComponent safra$clickableLink(String url) {
        ITextComponent link = safra$withStyle(new StringTextComponent(url), TextFormatting.BLUE, TextFormatting.UNDERLINE);
        try {
            Object style;
            try {
                style = Style.class.getField("EMPTY").get(null);
            } catch (ReflectiveOperationException ignored) {
                style = Style.class.newInstance();
            }
            ClickEvent event = new ClickEvent(ClickEvent.Action.OPEN_URL, url);
            Object clickedStyle = safra$invokeStyleClickEvent(style, event);
            if (clickedStyle != null) {
                link.getClass().getMethod("setStyle", Style.class).invoke(link, clickedStyle);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return link;
    }

    private static ITextComponent safra$withStyle(ITextComponent text, TextFormatting... formats) {
        Object current = text;
        for (TextFormatting format : formats) {
            for (String methodName : new String[]{"mergeStyle", "func_240699_a_", "withStyle"}) {
                try {
                    current = current.getClass().getMethod(methodName, TextFormatting.class).invoke(current, format);
                    break;
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return current instanceof ITextComponent ? (ITextComponent) current : text;
    }

    private static Object safra$invokeStyleClickEvent(Object style, ClickEvent event) {
        for (String methodName : new String[]{"setClickEvent", "withClickEvent"}) {
            try {
                return style.getClass().getMethod(methodName, ClickEvent.class).invoke(style, event);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static ITextComponent safra$append(ITextComponent left, ITextComponent right) {
        for (String methodName : new String[]{"appendSibling", "append"}) {
            try {
                Object result = left.getClass().getMethod(methodName, ITextComponent.class).invoke(left, right);
                return result instanceof ITextComponent ? (ITextComponent) result : left;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return new StringTextComponent(left.getString() + right.getString());
    }
}
