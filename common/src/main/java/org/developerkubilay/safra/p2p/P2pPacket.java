package org.developerkubilay.safra.p2p;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class P2pPacket {
    enum Type {
        OPEN(1),
        OPEN_ACK(2),
        DATA(3),
        ACK(4),
        CLOSE(5);

        private final int id;

        Type(int id) {
            this.id = id;
        }

        static Type fromId(int id) {
            for (Type value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return null;
        }
    }

    private final Type type;
    private final int token;
    private final int connectionId;
    private final int sequence;
    private final int acknowledgement;
    private final byte[] payload;

    private P2pPacket(Type type, int token, int connectionId, int sequence, int acknowledgement, byte[] payload) {
        this.type = type;
        this.token = token;
        this.connectionId = connectionId;
        this.sequence = sequence;
        this.acknowledgement = acknowledgement;
        this.payload = payload == null ? new byte[0] : payload;
    }

    static P2pPacket open(int token, int connectionId) {
        return new P2pPacket(Type.OPEN, token, connectionId, 0, 0, new byte[0]);
    }

    static P2pPacket openAck(int token, int connectionId) {
        return new P2pPacket(Type.OPEN_ACK, token, connectionId, 0, 0, new byte[0]);
    }

    static P2pPacket data(int token, int connectionId, int sequence, int acknowledgement, byte[] payload) {
        return new P2pPacket(Type.DATA, token, connectionId, sequence, acknowledgement, payload);
    }

    static P2pPacket ack(int token, int connectionId, int acknowledgement) {
        return new P2pPacket(Type.ACK, token, connectionId, 0, acknowledgement, new byte[0]);
    }

    static P2pPacket close(int token, int connectionId) {
        return new P2pPacket(Type.CLOSE, token, connectionId, 0, 0, new byte[0]);
    }

    Type type() {
        return type;
    }

    int token() {
        return token;
    }

    int connectionId() {
        return connectionId;
    }

    int sequence() {
        return sequence;
    }

    int acknowledgement() {
        return acknowledgement;
    }

    byte[] payload() {
        return payload;
    }

    byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(P2pConstants.HEADER_SIZE + payload.length);
        buffer.put(P2pConstants.PROTOCOL_VERSION);
        buffer.put((byte) type.id);
        buffer.putInt(token);
        buffer.putInt(connectionId);
        buffer.putInt(sequence);
        buffer.putInt(acknowledgement);
        buffer.put(payload);
        return buffer.array();
    }

    static P2pPacket decode(byte[] buffer, int length) {
        if (length < P2pConstants.HEADER_SIZE) {
            return null;
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, length);
        byte version = byteBuffer.get();
        if (version != P2pConstants.PROTOCOL_VERSION) {
            return null;
        }

        Type type = Type.fromId(byteBuffer.get() & 0xFF);
        if (type == null) {
            return null;
        }

        int token = byteBuffer.getInt();
        int connectionId = byteBuffer.getInt();
        int sequence = byteBuffer.getInt();
        int acknowledgement = byteBuffer.getInt();
        byte[] payload = Arrays.copyOfRange(buffer, P2pConstants.HEADER_SIZE, length);
        return new P2pPacket(type, token, connectionId, sequence, acknowledgement, payload);
    }
}
