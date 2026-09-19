package org.developerkubilay.safra.p2p;

public final class P2pOptionalIntegrations {
    private static final String VOICECHAT_API_CLASS = "de.maxhenkel.voicechat.api.ClientVoicechatSocket";

    private static volatile Boolean voiceChatAvailable;

    private P2pOptionalIntegrations() {
    }

    public static boolean isVoiceChatAvailable() {
        Boolean cached = voiceChatAvailable;
        if (cached == null) {
            cached = isClassPresent(VOICECHAT_API_CLASS);
            voiceChatAvailable = cached;
        }
        return cached;
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, P2pOptionalIntegrations.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
