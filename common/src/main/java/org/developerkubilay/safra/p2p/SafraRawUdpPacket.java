package org.developerkubilay.safra.p2p;

import de.maxhenkel.voicechat.api.RawUdpPacket;

import java.net.SocketAddress;

final class SafraRawUdpPacket implements RawUdpPacket {
    private final byte[] data;
    private final SocketAddress socketAddress;
    private final long timestamp;

    SafraRawUdpPacket(byte[] data, SocketAddress socketAddress, long timestamp) {
        this.data = data;
        this.socketAddress = socketAddress;
        this.timestamp = timestamp;
    }

    byte[] data() {
        return data;
    }

    SocketAddress socketAddress() {
        return socketAddress;
    }

    long timestamp() {
        return timestamp;
    }

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
