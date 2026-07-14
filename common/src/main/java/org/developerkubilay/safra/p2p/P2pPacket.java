package org.developerkubilay.safra.p2p;

import java.nio.ByteBuffer;
import java.util.Arrays;

record P2pPacket(Type type, int token, int connectionId, int sequence, int acknowledgement, byte[] payload) {
    private static final byte[] EMPTY_PAYLOAD = new byte[0];
    private static final ThreadLocal<ByteBuffer> ENCODE_BUFFER = ThreadLocal.withInitial(
        () -> ByteBuffer.allocate(P2pConstants.HEADER_SIZE + P2pConstants.MAX_PAYLOAD_SIZE)
    );

    enum Type {
        OPEN(1),
        OPEN_ACK(2),
        DATA(3),
        ACK(4),
        CLOSE(5),
        NACK(6);

        private final int id;

        Type(int id) {
            this.id = id;
        }

        static Type fromId(int id) {
            return switch (id) {
                case 1 -> OPEN;
                case 2 -> OPEN_ACK;
                case 3 -> DATA;
                case 4 -> ACK;
                case 5 -> CLOSE;
                case 6 -> NACK;
                default -> null;
            };
        }
    }

    static P2pPacket open(int token, int connectionId) {
        return new P2pPacket(Type.OPEN, token, connectionId, 0, 0, EMPTY_PAYLOAD);
    }

    static P2pPacket openAck(int token, int connectionId) {
        return new P2pPacket(Type.OPEN_ACK, token, connectionId, 0, 0, EMPTY_PAYLOAD);
    }

    static P2pPacket data(int token, int connectionId, int sequence, int acknowledgement, byte[] payload) {
        return new P2pPacket(Type.DATA, token, connectionId, sequence, acknowledgement, payload);
    }

    static P2pPacket ack(int token, int connectionId, int acknowledgement) {
        return ack(token, connectionId, acknowledgement, 0);
    }

    static P2pPacket ack(int token, int connectionId, int acknowledgement, int acknowledgementMask) {
        return new P2pPacket(Type.ACK, token, connectionId, 0, acknowledgement, controlPayload(acknowledgementMask));
    }

    static P2pPacket nack(int token, int connectionId, int missingSequence, int acknowledgement, int acknowledgementMask) {
        return new P2pPacket(Type.NACK, token, connectionId, missingSequence, acknowledgement, controlPayload(acknowledgementMask));
    }

    static P2pPacket close(int token, int connectionId) {
        return new P2pPacket(Type.CLOSE, token, connectionId, 0, 0, EMPTY_PAYLOAD);
    }

    P2pPacket {
        payload = payload == null ? EMPTY_PAYLOAD : payload;
    }

    int acknowledgementMask() {
        if ((type != Type.ACK && type != Type.NACK) || payload.length < Integer.BYTES) {
            return 0;
        }

        return readInt(payload, 0);
    }

    byte[] encode() {
        ByteBuffer buffer = ENCODE_BUFFER.get();
        buffer.clear();
        buffer.put(P2pConstants.PROTOCOL_VERSION);
        buffer.put((byte) type.id);
        buffer.putInt(token);
        buffer.putInt(connectionId);
        buffer.putInt(sequence);
        buffer.putInt(acknowledgement);
        buffer.put(payload);
        int length = buffer.position();
        return Arrays.copyOf(buffer.array(), length);
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
        int sequence = readInt(buffer, 10);
        int acknowledgement = readInt(buffer, 14);
        byte[] payload = length == P2pConstants.HEADER_SIZE
            ? EMPTY_PAYLOAD
            : Arrays.copyOfRange(buffer, P2pConstants.HEADER_SIZE, length);
        return new P2pPacket(type, token, connectionId, sequence, acknowledgement, payload);
    }

    private static byte[] controlPayload(int acknowledgementMask) {
        if (acknowledgementMask == 0) {
            return EMPTY_PAYLOAD;
        }

        return new byte[]{
            (byte) (acknowledgementMask >>> 24),
            (byte) (acknowledgementMask >>> 16),
            (byte) (acknowledgementMask >>> 8),
            (byte) acknowledgementMask
        };
    }

    private static int readInt(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFF) << 24)
            | ((buffer[offset + 1] & 0xFF) << 16)
            | ((buffer[offset + 2] & 0xFF) << 8)
            | (buffer[offset + 3] & 0xFF);
    }
}
