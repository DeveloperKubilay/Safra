package org.developerkubilay.safra.p2p;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicTokenHandler;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

final class P2pQuicTokenHandler implements QuicTokenHandler {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAC_LENGTH = 32;
    private static final int MAX_ADDRESS_LENGTH = 16;

    private final byte[] secret;

    private P2pQuicTokenHandler(byte[] secret) {
        this.secret = secret;
    }

    static P2pQuicTokenHandler create() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return new P2pQuicTokenHandler(secret);
    }

    @Override
    public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        if (address == null || address.getAddress() == null) {
            return false;
        }

        byte[] addressBytes = address.getAddress().getAddress();
        byte[] dcidBytes = new byte[dcid.readableBytes()];
        dcid.getBytes(dcid.readerIndex(), dcidBytes);
        byte[] mac = mac(addressBytes, dcidBytes);
        out.writeByte(addressBytes.length);
        out.writeBytes(addressBytes);
        out.writeBytes(mac);
        out.writeBytes(dcidBytes);
        return true;
    }

    @Override
    public int validateToken(ByteBuf token, InetSocketAddress address) {
        if (address == null || address.getAddress() == null || token.readableBytes() < 1 + MAC_LENGTH + 1) {
            return -1;
        }

        int offset = token.readerIndex();
        byte[] addressBytes = address.getAddress().getAddress();
        int encodedAddressLength = token.getUnsignedByte(offset);
        if (encodedAddressLength != addressBytes.length) {
            return -1;
        }

        int macOffset = offset + 1 + encodedAddressLength;
        int dcidOffset = macOffset + MAC_LENGTH;
        if (token.writerIndex() <= dcidOffset) {
            return -1;
        }

        byte[] encodedAddress = new byte[encodedAddressLength];
        token.getBytes(offset + 1, encodedAddress);
        if (!MessageDigest.isEqual(encodedAddress, addressBytes)) {
            return -1;
        }

        byte[] tokenMac = new byte[MAC_LENGTH];
        token.getBytes(macOffset, tokenMac);
        byte[] dcidBytes = new byte[token.writerIndex() - dcidOffset];
        token.getBytes(dcidOffset, dcidBytes);
        byte[] expectedMac = mac(addressBytes, dcidBytes);
        return MessageDigest.isEqual(tokenMac, expectedMac) ? dcidOffset - offset : -1;
    }

    @Override
    public int maxTokenLength() {
        return 1 + MAX_ADDRESS_LENGTH + MAC_LENGTH + Quic.MAX_CONN_ID_LEN;
    }

    private byte[] mac(byte[] addressBytes, byte[] dcidBytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            mac.update(addressBytes);
            mac.update(dcidBytes);
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not sign QUIC token", exception);
        }
    }
}
