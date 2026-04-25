package org.developerkubilay.safra.voicechat;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartingEvent;
import org.developerkubilay.safra.p2p.SafraVoiceClientSocket;
import org.developerkubilay.safra.p2p.SafraVoiceServerSocket;

public class SafraVoicechatPluginBase implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return "safra";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartingEvent.class, event ->
            event.setSocketImplementation(new SafraVoiceServerSocket()));
        registration.registerEvent(ClientVoicechatInitializationEvent.class, event ->
            event.setSocketImplementation(new SafraVoiceClientSocket()));
    }
}
