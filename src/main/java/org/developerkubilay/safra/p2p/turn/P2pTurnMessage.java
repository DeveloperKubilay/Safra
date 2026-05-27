package org.developerkubilay.safra.p2p.turn;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

record P2pTurnMessage(int type, byte[] transactionId, Map<Integer, byte[]> attributes) {
    static P2pTurnMessage parse(byte[] payload, int length) {
        if (length < P2pTurnProtocol.STUN_HEADER_SIZE) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload, 0, length).order(ByteOrder.BIG_ENDIAN);
        int type = Short.toUnsignedInt(buffer.getShort());
        int messageLength = Short.toUnsignedInt(buffer.getShort());
        if (buffer.getInt() != P2pTurnProtocol.MAGIC_COOKIE || messageLength > buffer.remaining()) {
            return null;
        }

        byte[] transactionId = new byte[12];
        buffer.get(transactionId);
        Map<Integer, byte[]> attributes = new HashMap<>();
        while (buffer.remaining() >= 4) {
            int attributeType = Short.toUnsignedInt(buffer.getShort());
            int attributeLength = Short.toUnsignedInt(buffer.getShort());
            if (attributeLength > buffer.remaining()) {
                return null;
            }
            byte[] value = new byte[attributeLength];
            buffer.get(value);
            attributes.putIfAbsent(attributeType, value);
            int padding = (4 - (attributeLength & 3)) & 3;
            if (padding > buffer.remaining()) {
                break;
            }
            buffer.position(buffer.position() + padding);
        }
        return new P2pTurnMessage(type, transactionId, attributes);
    }

    byte[] attribute(int type) {
        return attributes.get(type);
    }

    String stringAttribute(int type) {
        byte[] value = attribute(type);
        return value == null ? "" : new String(value, StandardCharsets.UTF_8).trim();
    }

    int errorCode() {
        byte[] value = attribute(P2pTurnProtocol.ATTR_ERROR_CODE);
        if (value == null || value.length < 4) {
            return 0;
        }
        return (value[2] & 0x07) * 100 + (value[3] & 0xFF);
    }

    String errorReason() {
        byte[] value = attribute(P2pTurnProtocol.ATTR_ERROR_CODE);
        if (value == null || value.length <= 4) {
            return "";
        }
        return new String(value, 4, value.length - 4, StandardCharsets.UTF_8).trim();
    }

    InetSocketAddress xorAddress(int attributeType) {
        byte[] value = attribute(attributeType);
        if (value == null || value.length < 8) {
            return null;
        }

        int family = value[1] & 0xFF;
        int port = Short.toUnsignedInt(ByteBuffer.wrap(value, 2, 2).getShort()) ^ (P2pTurnProtocol.MAGIC_COOKIE >>> 16);
        byte[] addressBytes;
        if (family == 0x01) {
            addressBytes = Arrays.copyOfRange(value, 4, 8);
            byte[] cookieBytes = ByteBuffer.allocate(4).putInt(P2pTurnProtocol.MAGIC_COOKIE).array();
            for (int index = 0; index < addressBytes.length; index++) {
                addressBytes[index] ^= cookieBytes[index];
            }
        } else if (family == 0x02 && value.length >= 20) {
            addressBytes = Arrays.copyOfRange(value, 4, 20);
            byte[] mask = ByteBuffer.allocate(16).putInt(P2pTurnProtocol.MAGIC_COOKIE).put(transactionId).array();
            for (int index = 0; index < addressBytes.length; index++) {
                addressBytes[index] ^= mask[index];
            }
        } else {
            return null;
        }

        try {
            return new InetSocketAddress(InetAddress.getByAddress(addressBytes), port);
        } catch (IOException exception) {
            return null;
        }
    }
}
