package org.developerkubilay.safra.p2p;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.Arrays;

public final class SafraPassthroughVoiceClientSocket implements ClientVoicechatSocket {
    private DatagramSocket socket;

    @Override
    public void open() throws Exception {
        socket = new DatagramSocket();
    }

    @Override
    public RawUdpPacket read() throws Exception {
        if (socket == null) {
            throw new IllegalStateException("Voice socket not opened yet");
        }

        byte[] buffer = new byte[8192];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return new SafraRawUdpPacket(
            Arrays.copyOf(packet.getData(), packet.getLength()),
            packet.getSocketAddress(),
            System.currentTimeMillis()
        );
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (socket == null) {
            return;
        }
        socket.send(new DatagramPacket(data, data.length, address));
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public boolean isClosed() {
        return socket == null || socket.isClosed();
    }
}
