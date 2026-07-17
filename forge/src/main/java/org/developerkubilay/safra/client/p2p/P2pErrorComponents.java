package org.developerkubilay.safra.client.p2p;

import net.minecraft.network.chat.Component;
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

    private static net.minecraft.network.chat.Style safra$withOpenUrl(net.minecraft.network.chat.Style style, String url) {
        try {
            Class<?> clickEventClass = Class.forName("net.minecraft.network.chat.ClickEvent");
            Object clickEvent;
            try {
                Class<?> openUrlClass = Class.forName("net.minecraft.network.chat.ClickEvent$OpenUrl");
                clickEvent = openUrlClass.getConstructor(java.net.URI.class).newInstance(java.net.URI.create(url));
            } catch (ReflectiveOperationException ignored) {
                Class<?> actionClass = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
                @SuppressWarnings({"rawtypes", "unchecked"}) Object action = Enum.valueOf((Class) actionClass, "OPEN_URL");
                clickEvent = clickEventClass.getConstructor(actionClass, String.class).newInstance(action, url);
            }
            for (java.lang.reflect.Method method : style.getClass().getMethods()) {
                if (method.getName().equals("withClickEvent") && method.getParameterCount() == 1) {
                    Object styled = method.invoke(style, clickEvent);
                    if (styled instanceof net.minecraft.network.chat.Style result) {
                        return result;
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return style;
    }

    private static Component safraError(Component details) {
        return Component.literal("Safra Error: ").append(details);
    }

    private static Component safra$discordLink() {
        String url = RemoteRendezvousConfigUpdater.discordUrl();
        return Component.literal("\n" + url)
            .withStyle(net.minecraft.ChatFormatting.BLUE, net.minecraft.ChatFormatting.UNDERLINE)
            .withStyle(style -> safra$withOpenUrl(style, url));
    }
}
