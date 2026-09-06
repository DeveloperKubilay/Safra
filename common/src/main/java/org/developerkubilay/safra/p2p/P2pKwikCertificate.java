package org.developerkubilay.safra.p2p;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Her paylaşım oturumunda üretilen QUIC sunucu kimliği.
 *
 * <p>Ek bir native kütüphane ya da sertifika dosyası taşımamak için, gerekli
 * küçük X.509 DER yapısını doğrudan JDK ile oluşturur. Sertifikanın açık kısmı
 * Safra'nın token'lı ilk paketiyle katılımcıya gider; Kwik bağlantısı bundan
 * sonra o sertifikaya sabitlenir.</p>
 */
final class P2pKwikCertificate {
    private static final char[] KEY_PASSWORD = "safra-kwik".toCharArray();
    private static final byte[] ECDSA_WITH_SHA256 = {
        0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02
    };
    private static final byte[] COMMON_NAME = {0x55, 0x04, 0x03};
    private static final Duration VALIDITY = Duration.ofHours(24);
    private static final DateTimeFormatter UTC_TIME =
        DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    private final KeyStore keyStore;
    private final X509Certificate certificate;

    private P2pKwikCertificate(KeyStore keyStore, X509Certificate certificate) {
        this.keyStore = keyStore;
        this.certificate = certificate;
    }

    static P2pKwikCertificate create() throws GeneralSecurityException {
        KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance("EC");
        keyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = keyGenerator.generateKeyPair();

        byte[] encodedCertificate = createCertificate(keyPair);
        X509Certificate certificate = decode(encodedCertificate);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        loadEmpty(keyStore, KEY_PASSWORD);
        keyStore.setKeyEntry("safra-p2p", keyPair.getPrivate(), KEY_PASSWORD, new Certificate[]{certificate});
        return new P2pKwikCertificate(keyStore, certificate);
    }

    KeyStore keyStore() {
        return keyStore;
    }

    byte[] encoded() throws GeneralSecurityException {
        return certificate.getEncoded();
    }

    static KeyStore trustStore(byte[] encodedCertificate) throws GeneralSecurityException {
        X509Certificate certificate = decode(encodedCertificate);
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        loadEmpty(trustStore, null);
        trustStore.setCertificateEntry("safra-p2p-host", certificate);
        return trustStore;
    }

    static char[] keyPassword() {
        return KEY_PASSWORD.clone();
    }

    private static void loadEmpty(KeyStore keyStore, char[] password) throws GeneralSecurityException {
        try {
            keyStore.load(null, password);
        } catch (java.io.IOException exception) {
            throw new GeneralSecurityException("Boş sertifika deposu başlatılamadı", exception);
        }
    }

    private static X509Certificate decode(byte[] encodedCertificate) throws GeneralSecurityException {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(encodedCertificate));
    }

    private static byte[] createCertificate(KeyPair keyPair) throws GeneralSecurityException {
        byte[] signatureAlgorithm = sequence(objectIdentifier(ECDSA_WITH_SHA256));
        byte[] distinguishedName = sequence(set(sequence(objectIdentifier(COMMON_NAME), utf8String("Safra P2P Host"))));
        Instant now = Instant.now();
        byte[] validity = sequence(utcTime(now.minusSeconds(60L)), utcTime(now.plus(VALIDITY)));
        byte[] serial = new BigInteger(96, new SecureRandom()).add(BigInteger.ONE).toByteArray();
        byte[] tbsCertificate = sequence(
            explicit(0, integer(new byte[]{2})),
            integer(serial),
            signatureAlgorithm,
            distinguishedName,
            validity,
            distinguishedName,
            keyPair.getPublic().getEncoded()
        );

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbsCertificate);
        return sequence(tbsCertificate, signatureAlgorithm, bitString(signer.sign()));
    }

    private static byte[] sequence(byte[]... values) {
        return tagged(0x30, join(values));
    }

    private static byte[] set(byte[] value) {
        return tagged(0x31, value);
    }

    private static byte[] explicit(int tagNumber, byte[] value) {
        return tagged(0xA0 | tagNumber, value);
    }

    private static byte[] integer(byte[] value) {
        if ((value[0] & 0x80) != 0) {
            byte[] positive = new byte[value.length + 1];
            System.arraycopy(value, 0, positive, 1, value.length);
            value = positive;
        }
        return tagged(0x02, value);
    }

    private static byte[] objectIdentifier(byte[] value) {
        return tagged(0x06, value);
    }

    private static byte[] utf8String(String value) {
        return tagged(0x0C, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] utcTime(Instant value) {
        return tagged(0x17, UTC_TIME.format(value).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] bitString(byte[] value) {
        byte[] withUnusedBits = new byte[value.length + 1];
        System.arraycopy(value, 0, withUnusedBits, 1, value.length);
        return tagged(0x03, withUnusedBits);
    }

    private static byte[] tagged(int tag, byte[] value) {
        byte[] length = length(value.length);
        byte[] encoded = new byte[1 + length.length + value.length];
        encoded[0] = (byte) tag;
        System.arraycopy(length, 0, encoded, 1, length.length);
        System.arraycopy(value, 0, encoded, 1 + length.length, value.length);
        return encoded;
    }

    private static byte[] length(int value) {
        if (value < 128) {
            return new byte[]{(byte) value};
        }
        int bytes = Integer.BYTES - Integer.numberOfLeadingZeros(value) / Byte.SIZE;
        byte[] encoded = new byte[bytes + 1];
        encoded[0] = (byte) (0x80 | bytes);
        for (int index = bytes; index > 0; index--) {
            encoded[index] = (byte) value;
            value >>>= Byte.SIZE;
        }
        return encoded;
    }

    private static byte[] join(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, joined, offset, value.length);
            offset += value.length;
        }
        return joined;
    }
}


