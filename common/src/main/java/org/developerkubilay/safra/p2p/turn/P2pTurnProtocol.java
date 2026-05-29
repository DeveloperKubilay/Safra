package org.developerkubilay.safra.p2p.turn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class P2pTurnProtocol {
    static final int STUN_HEADER_SIZE = 20;
    static final int MAGIC_COOKIE = 0x2112A442;
    static final int ATTR_USERNAME = 0x0006;
    static final int ATTR_MESSAGE_INTEGRITY = 0x0008;
    static final int ATTR_ERROR_CODE = 0x0009;
    static final int ATTR_LIFETIME = 0x000D;
    static final int ATTR_XOR_PEER_ADDRESS = 0x0012;
    static final int ATTR_DATA = 0x0013;
    static final int ATTR_REALM = 0x0014;
    static final int ATTR_NONCE = 0x0015;
    static final int ATTR_XOR_RELAYED_ADDRESS = 0x0016;
    static final int ATTR_REQUESTED_TRANSPORT = 0x0019;
    static final int TURN_ALLOCATE_REQUEST = 0x0003;
    static final int TURN_ALLOCATE_RESPONSE = 0x0103;
    static final int TURN_REFRESH_REQUEST = 0x0004;
    static final int TURN_REFRESH_RESPONSE = 0x0104;
    static final int TURN_CREATE_PERMISSION_REQUEST = 0x0008;
    static final int TURN_CREATE_PERMISSION_RESPONSE = 0x0108;
    static final int TURN_SEND_INDICATION = 0x0016;
    static final int TURN_DATA_INDICATION = 0x0017;
    static final int ERROR_UNAUTHORIZED = 401;
    static final int ERROR_STALE_NONCE = 438;
    static final int REQUESTED_TRANSPORT_UDP = 17;

    private P2pTurnProtocol() {
    }

    static byte[] buildRequest(
        SecureRandom random,
        String username,
        String realm,
        String nonce,
        String credential,
        int requestType,
        AttributeWriter writer,
        AuthMode authMode
    ) throws IOException {
        byte[] transactionId = new byte[12];
        random.nextBytes(transactionId);

        ByteArrayOutputStream attributes = new ByteArrayOutputStream();
        writer.write(attributes, transactionId);
        if (authMode == AuthMode.NONE) {
            return finalizeMessage(requestType, transactionId, attributes.toByteArray());
        }

        putAttribute(attributes, ATTR_USERNAME, username.getBytes(StandardCharsets.UTF_8));
        putAttribute(attributes, ATTR_REALM, realm.getBytes(StandardCharsets.UTF_8));
        putAttribute(attributes, ATTR_NONCE, nonce.getBytes(StandardCharsets.UTF_8));

        byte[] prefix = attributes.toByteArray();
        int messageLength = prefix.length + 24;
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        writeHeader(message, requestType, transactionId, messageLength);
        message.write(prefix);
        writeShort(message, ATTR_MESSAGE_INTEGRITY);
        writeShort(message, 20);
        message.write(new byte[20]);

        byte[] encoded = message.toByteArray();
        byte[] hmac = hmacSha1(Arrays.copyOf(encoded, STUN_HEADER_SIZE + prefix.length), md5Key(username, realm, credential));
        System.arraycopy(hmac, 0, encoded, encoded.length - 20, 20);
        return encoded;
    }

    static byte[] buildSendIndication(SecureRandom random, InetSocketAddress remoteAddress, byte[] payload) throws IOException {
        byte[] transactionId = new byte[12];
        random.nextBytes(transactionId);
        ByteArrayOutputStream attributes = new ByteArrayOutputStream();
        putXorPeerAddress(attributes, remoteAddress, transactionId);
        putAttribute(attributes, ATTR_DATA, payload);
        return finalizeMessage(TURN_SEND_INDICATION, transactionId, attributes.toByteArray());
    }

    static InetSocketAddress resolveServer(P2pTurnCredentials.TurnServer server) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(server.host());
        Arrays.sort(addresses, (left, right) -> Boolean.compare(!(left instanceof Inet4Address), !(right instanceof Inet4Address)));
        if (addresses.length == 0) {
            throw new IOException("TURN sunucusu cozulmedi: " + server.host());
        }
        return new InetSocketAddress(addresses[0], server.port());
    }

    static void putRequestedTransport(ByteArrayOutputStream out, int transport) throws IOException {
        putAttribute(out, ATTR_REQUESTED_TRANSPORT, new byte[]{(byte) transport, 0, 0, 0});
    }

    static void putLifetime(ByteArrayOutputStream out, int lifetimeSeconds) throws IOException {
        putAttribute(out, ATTR_LIFETIME, ByteBuffer.allocate(4).putInt(lifetimeSeconds).array());
    }

    static void putXorPeerAddress(ByteArrayOutputStream out, InetSocketAddress address, byte[] transactionId) throws IOException {
        putAttribute(out, ATTR_XOR_PEER_ADDRESS, encodeXorAddress(address, transactionId));
    }

    static int expectedSuccessType(int requestType) {
        if (requestType == TURN_ALLOCATE_REQUEST) {
            return TURN_ALLOCATE_RESPONSE;
        } else if (requestType == TURN_REFRESH_REQUEST) {
            return TURN_REFRESH_RESPONSE;
        } else if (requestType == TURN_CREATE_PERMISSION_REQUEST) {
            return TURN_CREATE_PERMISSION_RESPONSE;
        } else {
            return 0;
        }
    }

    static IOException turnError(int requestType, P2pTurnMessage response) {
        String type;
        if (requestType == TURN_ALLOCATE_REQUEST) {
            type = "allocate";
        } else if (requestType == TURN_REFRESH_REQUEST) {
            type = "refresh";
        } else if (requestType == TURN_CREATE_PERMISSION_REQUEST) {
            type = "create-permission";
        } else {
            type = "request";
        }
        int errorCode = response.errorCode();
        String reason = response.errorReason();
        return new IOException("TURN " + type + " patladi: " + errorCode + (reason.trim().isEmpty() ? "" : " " + reason));
    }

    static String transactionKey(byte[] transactionId) {
        StringBuilder builder = new StringBuilder(transactionId.length * 2);
        for (byte value : transactionId) {
            builder.append(Character.forDigit((value >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    static String permissionKey(InetSocketAddress address) {
        InetAddress inetAddress = address.getAddress();
        String host = inetAddress == null ? address.getHostString() : inetAddress.getHostAddress();
        return host + ":" + address.getPort();
    }

    private static byte[] finalizeMessage(int type, byte[] transactionId, byte[] attributes) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        writeHeader(message, type, transactionId, attributes.length);
        message.write(attributes);
        return message.toByteArray();
    }

    private static void writeHeader(ByteArrayOutputStream out, int type, byte[] transactionId, int length) throws IOException {
        writeShort(out, type);
        writeShort(out, length);
        out.write(ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array());
        out.write(transactionId);
    }

    private static void putAttribute(ByteArrayOutputStream out, int type, byte[] value) throws IOException {
        writeShort(out, type);
        writeShort(out, value.length);
        out.write(value);
        int padding = (4 - (value.length & 3)) & 3;
        for (int index = 0; index < padding; index++) {
            out.write(0);
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int value) throws IOException {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static byte[] encodeXorAddress(InetSocketAddress address, byte[] transactionId) {
        InetAddress inetAddress = Objects.requireNonNull(address.getAddress(), "TURN address unresolved");
        byte[] rawAddress = inetAddress.getAddress();
        ByteBuffer buffer = ByteBuffer.allocate(rawAddress.length == 16 ? 20 : 8).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) 0);
        buffer.put((byte) (rawAddress.length == 16 ? 0x02 : 0x01));
        buffer.putShort((short) (address.getPort() ^ (MAGIC_COOKIE >>> 16)));

        byte[] mask = rawAddress.length == 16
            ? ByteBuffer.allocate(16).putInt(MAGIC_COOKIE).put(transactionId).array()
            : ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array();
        for (int index = 0; index < rawAddress.length; index++) {
            buffer.put((byte) (rawAddress[index] ^ mask[index]));
        }
        return buffer.array();
    }

    private static byte[] md5Key(String username, String realm, String credential) throws IOException {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            return messageDigest.digest((username + ":" + realm + ":" + credential).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IOException("TURN auth anahtari olusmadi", exception);
        }
    }

    private static byte[] hmacSha1(byte[] message, byte[] key) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (Exception exception) {
            throw new IOException("TURN HMAC olusmadi", exception);
        }
    }

    @FunctionalInterface
    interface AttributeWriter {
        void write(ByteArrayOutputStream out, byte[] transactionId) throws IOException;
    }

    enum AuthMode {
        NONE,
        LONG_TERM
    }
}
