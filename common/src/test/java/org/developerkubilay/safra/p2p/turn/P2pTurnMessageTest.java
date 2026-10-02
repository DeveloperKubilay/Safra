package org.developerkubilay.safra.p2p.turn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class P2pTurnMessageTest {
    private static final byte[] TRANSACTION_ID = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    @Test
    void acceptsHeaderOnlyMessage() {
        P2pTurnMessage parsed = parse(message(0, new byte[0]));
        assertNotNull(parsed);
        assertArrayEquals(TRANSACTION_ID, parsed.transactionId());
        assertTrue(parsed.attributes().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.1", "2001:db8::1"})
    void roundTripsSendIndicationWithPaddedData(String host) throws Exception {
        InetSocketAddress peer = new InetSocketAddress(InetAddress.getByName(host), 25565);
        byte[] data = {11, 22, 33};
        byte[] encoded = P2pTurnProtocol.buildSendIndication(new SecureRandom(), peer, data);
        P2pTurnMessage parsed = parse(encoded);
        assertNotNull(parsed);
        assertEquals(P2pTurnProtocol.TURN_SEND_INDICATION, parsed.type());
        assertEquals(peer, parsed.xorAddress(P2pTurnProtocol.ATTR_XOR_PEER_ADDRESS));
        assertArrayEquals(data, parsed.attribute(P2pTurnProtocol.ATTR_DATA));
    }

    @Test
    void ignoresAttributesBeyondDeclaredMessage() {
        byte[] payload = message(0, attribute(P2pTurnProtocol.ATTR_NONCE, new byte[]{1, 2, 3, 4}));
        P2pTurnMessage parsed = parse(payload);
        assertNotNull(parsed);
        assertTrue(parsed.attributes().isEmpty());
    }

    @Test
    void doesNotUseTrailingBytesToCompleteAnAttribute() {
        byte[] payload = message(4, attribute(P2pTurnProtocol.ATTR_NONCE, new byte[]{1, 2, 3, 4}));
        assertNull(parse(payload));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8, 12})
    void rejectsBodyTruncatedInsideTransactionIdAllowance(int declaredLength) {
        // The old check counted the 12 transaction-ID bytes as available body bytes.
        assertNull(parse(message(declaredLength, new byte[0])));
    }

    @Test
    void respectsSuppliedDatagramLengthRatherThanBackingArray() {
        byte[] payload = message(8, attribute(P2pTurnProtocol.ATTR_NONCE, new byte[]{1, 2, 3, 4}));
        assertNull(P2pTurnMessage.parse(payload, 20));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5})
    void rejectsUnalignedBodyLength(int declaredLength) {
        assertNull(parse(message(declaredLength, new byte[8])));
    }

    @Test
    void rejectsAttributeWhosePaddingExceedsDeclaredBody() {
        byte[] body = ByteBuffer.allocate(8).putShort((short) P2pTurnProtocol.ATTR_NONCE)
            .putShort((short) 5).put(new byte[4]).array();
        assertNull(parse(message(body.length, body)));
    }

    @Test
    void acceptsPaddingWithNonzeroBytes() {
        byte[] body = attribute(P2pTurnProtocol.ATTR_NONCE, new byte[]{42});
        Arrays.fill(body, 5, 8, (byte) 0xFF);
        P2pTurnMessage parsed = parse(message(body.length, body));
        assertNotNull(parsed);
        assertArrayEquals(new byte[]{42}, parsed.attribute(P2pTurnProtocol.ATTR_NONCE));
    }

    @Test
    void keepsFirstDuplicateAttribute() {
        byte[] first = attribute(P2pTurnProtocol.ATTR_NONCE, new byte[]{1});
        byte[] second = attribute(P2pTurnProtocol.ATTR_NONCE, new byte[]{2});
        byte[] body = ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
        P2pTurnMessage parsed = parse(message(body.length, body));
        assertNotNull(parsed);
        assertArrayEquals(new byte[]{1}, parsed.attribute(P2pTurnProtocol.ATTR_NONCE));
    }

    @Test
    void rejectsInvalidCookie() {
        byte[] payload = message(0, new byte[0]);
        payload[4] ^= 1;
        assertNull(parse(payload));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x4000, 0x8000, 0xC000})
    void rejectsNonStunMessageType(int bits) {
        byte[] payload = message(0, new byte[0]);
        ByteBuffer.wrap(payload).putShort((short) (P2pTurnProtocol.TURN_DATA_INDICATION | bits));
        assertNull(parse(payload));
    }

    @Test
    void rejectsInvalidInputBoundsWithoutThrowing() {
        assertNull(P2pTurnMessage.parse(null, 20));
        assertNull(P2pTurnMessage.parse(new byte[20], -1));
        assertNull(P2pTurnMessage.parse(new byte[19], 19));
        assertNull(P2pTurnMessage.parse(new byte[20], 21));
    }

    private static P2pTurnMessage parse(byte[] payload) {
        return P2pTurnMessage.parse(payload, payload.length);
    }

    private static byte[] message(int declaredLength, byte[] body) {
        return ByteBuffer.allocate(20 + body.length)
            .putShort((short) P2pTurnProtocol.TURN_DATA_INDICATION)
            .putShort((short) declaredLength)
            .putInt(P2pTurnProtocol.MAGIC_COOKIE).put(TRANSACTION_ID).put(body).array();
    }

    private static byte[] attribute(int type, byte[] value) {
        return ByteBuffer.allocate(4 + ((value.length + 3) & ~3))
            .putShort((short) type).putShort((short) value.length).put(value).array();
    }
}
