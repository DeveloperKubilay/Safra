package org.developerkubilay.safra.p2p;

import de.maxhenkel.voicechat.api.RawUdpPacket;

import java.net.SocketAddress;

record SafraRawUdpPacket(byte[] data, SocketAddress socketAddress, long timestamp) implements RawUdpPacket {
    @Override
    public byte[] getData() {
        return data;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public SocketAddress getSocketAddress() {
        return socketAddress;
    }
}
