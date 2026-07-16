package org.developerkubilay.safra.p2p;

public final class P2pOptionalIntegrations {
    private static final String VOICECHAT_API_CLASS = "de.maxhenkel.voicechat.api.ClientVoicechatSocket";
    private static volatile Boolean voiceChatAvailable;

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

}
