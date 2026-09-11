package org.developerkubilay.safra.p2p;

import java.nio.ByteBuffer;
import java.util.Arrays;

record P2pPacket(Type type, int token, int connectionId, byte[] payload) {
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    enum Type {
        PUNCH(4),
        CLOSE(5),
        QUIC_OPEN(7),
        QUIC_CERTIFICATE(8),
        QUIC_DATA(9);

        private final int id;

        Type(int id) {
            this.id = id;
        }

        static Type fromId(int id) {
            return switch (id) {
                case 4 -> PUNCH;
                case 5 -> CLOSE;
                case 7 -> QUIC_OPEN;
                case 8 -> QUIC_CERTIFICATE;
                case 9 -> QUIC_DATA;
                default -> null;
            };
        }
    }

    static P2pPacket punch(int token) {
        return new P2pPacket(Type.PUNCH, token, 0, EMPTY_PAYLOAD);
    }

    static P2pPacket quicOpen(int token, int connectionId) {
        return new P2pPacket(Type.QUIC_OPEN, token, connectionId, EMPTY_PAYLOAD);
    }

    static P2pPacket quicCertificate(int token, int connectionId, byte[] certificate) {
        return new P2pPacket(Type.QUIC_CERTIFICATE, token, connectionId, certificate);
    }

    static P2pPacket quicData(int token, int connectionId, byte[] payload) {
        return new P2pPacket(Type.QUIC_DATA, token, connectionId, payload);
    }

    static P2pPacket close(int token, int connectionId) {
        return new P2pPacket(Type.CLOSE, token, connectionId, EMPTY_PAYLOAD);
    }

    P2pPacket {
        payload = payload == null ? EMPTY_PAYLOAD : payload;
    }

    byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(P2pConstants.HEADER_SIZE + payload.length);
        buffer.put(P2pConstants.PROTOCOL_VERSION);
        buffer.put((byte) type.id);
        buffer.putInt(token);
        buffer.putInt(connectionId);
        buffer.put(payload);
        return buffer.array();
    }

    static P2pPacket decode(byte[] buffer, int length) {
        if (length < P2pConstants.HEADER_SIZE) {
            return null;
        }

        if (buffer[0] != P2pConstants.PROTOCOL_VERSION) {
            return null;
        }

        Type type = Type.fromId(Byte.toUnsignedInt(buffer[1]));
        if (type == null) {
            return null;
        }

        int token = readInt(buffer, 2);
        int connectionId = readInt(buffer, 6);
        byte[] payload = length == P2pConstants.HEADER_SIZE
            ? EMPTY_PAYLOAD
            : Arrays.copyOfRange(buffer, P2pConstants.HEADER_SIZE, length);
        return new P2pPacket(type, token, connectionId, payload);
    }

    private static int readInt(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFF) << 24)
            | ((buffer[offset + 1] & 0xFF) << 16)
            | ((buffer[offset + 2] & 0xFF) << 8)
            | (buffer[offset + 3] & 0xFF);
    }
}
