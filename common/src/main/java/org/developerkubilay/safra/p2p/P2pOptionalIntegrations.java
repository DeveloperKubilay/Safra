package org.developerkubilay.safra.p2p;

public final class P2pOptionalIntegrations {
    private static final String VOICECHAT_API_CLASS = "de.maxhenkel.voicechat.api.ClientVoicechatSocket";
    private static final String GEYSER_API_CLASS = "org.geysermc.geyser.api.GeyserApi";

    private static volatile Boolean voiceChatAvailable;
    private static volatile Boolean geyserAvailable;
    private static volatile Class<?> geyserApiClass;

    private P2pOptionalIntegrations() {
    }

    public static boolean isVoiceChatAvailable() {
        Boolean cached = voiceChatAvailable;
        if (cached != null) {
            return cached;
        }

        boolean available;
        try {
            Class.forName(VOICECHAT_API_CLASS, false, P2pOptionalIntegrations.class.getClassLoader());
            available = true;
        } catch (ClassNotFoundException exception) {
            available = false;
        }

        voiceChatAvailable = available;
        return available;
    }

    public static boolean isGeyserAvailable() {
        Boolean cached = geyserAvailable;
        if (cached != null) {
            return cached;
        }

        boolean available;
        try {
            geyserApiClass(false);
            available = true;
        } catch (ClassNotFoundException exception) {
            available = false;
        }

        geyserAvailable = available;
        return available;
    }

    public static void setGeyserApiClass(Class<?> apiClass) {
        geyserApiClass = apiClass;
        geyserAvailable = null;
    }

    public static GeyserListener geyserListener() {
        if (!isGeyserAvailable()) {
            return null;
        }

        try {
            Class<?> apiClass = geyserApiClass(true);
            Object api = apiClass.getMethod("api").invoke(null);
            if (api == null) {
                return null;
            }
            java.lang.reflect.Method listenerMethod = apiClass.getMethod("bedrockListener");
            Object listener = listenerMethod.invoke(api);
            if (listener == null) {
                return null;
            }
            Class<?> listenerType = listenerMethod.getReturnType();
            Object addressValue = listenerType.getMethod("address").invoke(listener);
            Object portValue = listenerType.getMethod("port").invoke(listener);
            if (!(addressValue instanceof String address) || !(portValue instanceof Number port)) {
                return null;
            }
            int portNumber = port.intValue();
            return address.isBlank() || portNumber < 1 || portNumber > 65535
                ? null
                : new GeyserListener(address, portNumber);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static Class<?> geyserApiClass(boolean initialize) throws ClassNotFoundException {
        Class<?> apiClass = geyserApiClass;
        if (apiClass != null) {
            return apiClass;
        }
        return Class.forName(GEYSER_API_CLASS, initialize, P2pOptionalIntegrations.class.getClassLoader());
    }

    public record GeyserListener(String address, int port) {
    }
}
