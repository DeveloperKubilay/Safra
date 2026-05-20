package org.developerkubilay.safra.p2p;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicCodecBuilder;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class NettyQuicSupport {
    private static final Logger LOG = LoggerFactory.getLogger(NettyQuicSupport.class);
    private static final byte[] QUIC_PUNCH_PAYLOAD = "safra-quic-punch".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HANDSHAKE_MAGIC = "safra-quic-auth".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HANDSHAKE_ACK = "safra-quic-ok".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROBE_PAYLOAD = "safra-quic-probe".getBytes(StandardCharsets.US_ASCII);
    private static final int HANDSHAKE_SIZE = HANDSHAKE_MAGIC.length + 1 + Integer.BYTES;

    private NettyQuicSupport() {
    }

    static void verifyRuntimeAvailability() throws IOException {
        ensureAvailable();
        X509Certificate certificate = loadEmbeddedCertificate();
        loadEmbeddedPrivateKey();
        decodeCertificate(encodeCertificate(certificate));
    }

    static void probeRuntimeAvailability() throws IOException {
        try {
            verifyRuntimeAvailability();
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Safra QUIC probe failed", exception);
        }
    }

    static P2pQuicHostSession startHost(Logger logger, InetAddress targetAddress, int tcpPort, int bindPort, int tunnelToken) throws IOException {
        ensureAvailable();

        X509Certificate cert = loadEmbeddedCertificate();
        java.security.PrivateKey key = loadEmbeddedPrivateKey();
        String encodedCertificate = encodeCertificate(cert);
        
        QuicSslContext sslContext = QuicSslContextBuilder.forServer(key, null, cert)
            .applicationProtocols(P2pConstants.QUIC_APPLICATION_PROTOCOL)
            .build();
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel channel = null;

        try {
            ChannelHandler codec = applyCommonQuicLimits(new QuicServerCodecBuilder())
                .sslContext(sslContext)
                .tokenHandler(null)
                .handler(new ChannelInboundHandlerAdapter())
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel channel) {
                        channel.pipeline().addLast(new ServerHandshakeHandler(logger, channel, targetAddress, tcpPort, tunnelToken));
                    }
                })
                .build();

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel channel) {
                        channel.pipeline().addLast(codec);
                    }
                });

            channel = awaitChannel(bootstrap.bind(bindPort), "Safra QUIC host UDP bind failed");
            int localPort = ((InetSocketAddress) channel.localAddress()).getPort();
            logger.info("Safra QUIC host listening on UDP {}", localPort);
            return new NettyQuicHostSession(logger, group, channel, encodedCertificate, localPort);
        } catch (IOException exception) {
            closeQuietly(channel);
            group.shutdownGracefully().syncUninterruptibly();
            throw exception;
        } catch (RuntimeException exception) {
            closeQuietly(channel);
            group.shutdownGracefully().syncUninterruptibly();
            throw exception;
        }
    }

    static void bridgeClient(Logger logger, Socket localSocket, InetSocketAddress remoteAddress,
                             int quicPort, int localPort, int tunnelToken, String encodedCertificate) throws IOException {
        bridgeClient(logger, localSocket, remoteAddress, quicPort, localPort, tunnelToken, encodedCertificate, null, P2pConstants.QUIC_CONNECT_TIMEOUT_MS);
    }

    static void bridgeClient(Logger logger, Socket localSocket, InetSocketAddress remoteAddress,
                             int quicPort, int localPort, int tunnelToken, String encodedCertificate,
                             Runnable onConnected) throws IOException {
        bridgeClient(logger, localSocket, remoteAddress, quicPort, localPort, tunnelToken, encodedCertificate, onConnected, P2pConstants.QUIC_CONNECT_TIMEOUT_MS);
    }

    static void bridgeClient(Logger logger, Socket localSocket, InetSocketAddress remoteAddress,
                             int quicPort, int localPort, int tunnelToken, String encodedCertificate,
                             Runnable onConnected, int connectTimeoutMs) throws IOException {
        ensureAvailable();
        P2pSockets.tune(localSocket);
        long connectStartedAt = System.nanoTime();

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel udpChannel = null;
        QuicChannel quicChannel = null;

        try {
            QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                .applicationProtocols(P2pConstants.QUIC_APPLICATION_PROTOCOL)
                .trustManager(decodeCertificate(encodedCertificate))
                .build();

            ChannelHandler codec = applyCommonQuicLimits(new QuicClientCodecBuilder())
                .sslContext(sslContext)
                .build();

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel channel) {
                        channel.pipeline().addLast(codec);
                    }
                });

            udpChannel = awaitChannel(bootstrap.bind(localPort > 0 ? localPort : 0), "Safra QUIC client UDP bind failed");
            InetSocketAddress quicAddress = new InetSocketAddress(remoteAddress.getAddress(), quicPort);
            quicChannel = awaitFuture(
                QuicChannel.newBootstrap(udpChannel)
                    .handler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(quicAddress)
                    .connect(),
                "Safra QUIC connect failed",
                connectTimeoutMs
            );
            QuicStreamChannel streamChannel = awaitFuture(
                quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, null),
                "Safra QUIC stream open failed",
                connectTimeoutMs
            );
            ClientHandshakeAckHandler handshakeHandler = new ClientHandshakeAckHandler();
            streamChannel.pipeline().addLast(handshakeHandler);
            awaitChannelFuture(streamChannel.writeAndFlush(handshakePayload(tunnelToken)), "Safra QUIC auth send failed", connectTimeoutMs);
            NettyQuicTcpBridge bridge = NettyQuicTcpBridge.attach(logger, streamChannel, localSocket);
            handshakeHandler.await(connectTimeoutMs);
            long connectElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStartedAt);
            logger.info("Safra QUIC client connected to {} in {} ms", quicAddress, connectElapsedMs);
            if (onConnected != null) {
                onConnected.run();
            }
            bridge.await();
        } finally {
            closeQuietly(quicChannel);
            closeQuietly(udpChannel);
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    static void prePunchClientPath(Logger logger, InetSocketAddress remoteAddress, int quicPort, int localPort) {
        if (remoteAddress == null || remoteAddress.isUnresolved() || quicPort < 1 || localPort < 1) {
            return;
        }

        InetSocketAddress quicAddress = new InetSocketAddress(remoteAddress.getAddress(), quicPort);
        try (DatagramSocket socket = P2pSockets.datagramSocket(localPort)) {
            long[] delaysMs = {0L, 80L, 180L, 350L};
            for (int attempt = 0; attempt < delaysMs.length; attempt++) {
                if (delaysMs[attempt] > 0L) {
                    try {
                        Thread.sleep(delaysMs[attempt] - delaysMs[attempt - 1]);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                socket.send(new java.net.DatagramPacket(QUIC_PUNCH_PAYLOAD, QUIC_PUNCH_PAYLOAD.length, quicAddress));
            }
            logger.debug("Safra QUIC client pre-punched {} from local UDP {}", quicAddress, socket.getLocalPort());
        } catch (IOException exception) {
            logger.debug("Safra QUIC client pre-punch failed for {}: {}", quicAddress, exception.toString());
        }
    }

    private static volatile boolean nativeLoadAttempted = false;

    private static void ensureAvailable() throws IOException {
        if (!nativeLoadAttempted) {
            synchronized (NettyQuicSupport.class) {
                if (!nativeLoadAttempted) {
                    nativeLoadAttempted = true;
                    triggerQuicheLoading();
                    if (!Quic.isAvailable() && !disableBundledNativeLoad()) {
                        tryLoadBundledNative();
                        triggerQuicheLoading();
                    }
                }
            }
        }

        if (Quic.isAvailable()) return;

        Throwable cause = Quic.unavailabilityCause();
        LOG.warn("Safra QUIC is unavailable: {}", cause != null ? cause.getMessage() : "unknown");
        throw new IOException("Netty QUIC is unavailable" + (cause != null ? ": " + cause.getMessage() : ""), cause);
    }

    private static boolean disableBundledNativeLoad() {
        return Boolean.getBoolean("safra.p2p.disableBundledQuicNativeLoad");
    }

    private static void triggerQuicheLoading() {
        try {
            Class.forName("io.netty.handler.codec.quic.Quiche", true, NettyQuicSupport.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
        }
    }

    private static void tryLoadBundledNative() {
        try {
            Path nativeLibrary = P2pQuicNativeManager.ensureBundledNativeAvailable();
            System.load(nativeLibrary.toAbsolutePath().toString());
            LOG.debug("Safra QUIC bundled native loaded from {}", nativeLibrary);
        } catch (Throwable throwable) {
            LOG.debug("Safra QUIC bundled native load failed, Quiche NativeLibraryLoader fallback: {}", throwable.toString());
        }
    }

    private static X509Certificate loadEmbeddedCertificate() throws IOException {
        try (InputStream stream = NettyQuicSupport.class.getResourceAsStream("/assets/safra/safra-quic.crt")) {
            if (stream == null) throw new IOException("Embedded QUIC certificate not found");
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
        } catch (CertificateException exception) {
            throw new IOException("Could not load embedded QUIC certificate", exception);
        }
    }

    private static java.security.PrivateKey loadEmbeddedPrivateKey() throws IOException {
        try (InputStream stream = NettyQuicSupport.class.getResourceAsStream("/assets/safra/safra-quic.key")) {
            if (stream == null) throw new IOException("Embedded QUIC private key not found");
            String keyPem = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            keyPem = keyPem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
            byte[] keyBytes = java.util.Base64.getDecoder().decode(keyPem);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyBytes));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IOException("Could not load embedded QUIC private key", exception);
        }
    }

    private static String encodeCertificate(X509Certificate certificate) throws IOException {
        try {
            return Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (CertificateEncodingException exception) {
            throw new IOException("Could not encode QUIC session certificate", exception);
        }
    }

    private static X509Certificate decodeCertificate(String encodedCertificate) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(Base64.getDecoder().decode(encodedCertificate))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        } catch (IllegalArgumentException | CertificateException exception) {
            throw new IOException("Could not decode QUIC session certificate", exception);
        }
    }

    private static <B extends QuicCodecBuilder<B>> B applyCommonQuicLimits(B builder) {
        return builder
            .maxIdleTimeout(P2pConstants.QUIC_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .initialMaxData(P2pConstants.TCP_BUFFER_SIZE * 4L)
            .initialMaxStreamDataBidirectionalLocal(P2pConstants.TCP_BUFFER_SIZE)
            .initialMaxStreamDataBidirectionalRemote(P2pConstants.TCP_BUFFER_SIZE)
            .initialMaxStreamsBidirectional(1);
    }

    private static Channel awaitChannel(ChannelFuture future, String message) throws IOException {
        return awaitChannel(future, message, P2pConstants.QUIC_CONNECT_TIMEOUT_MS);
    }

    private static Channel awaitChannel(ChannelFuture future, String message, int timeoutMs) throws IOException {
        if (!future.awaitUninterruptibly(timeoutMs)) {
            future.cancel(false);
            throw new IOException(message + ": timeout");
        }
        if (!future.isSuccess()) {
            throw new IOException(message + ": " + future.cause().getMessage(), future.cause());
        }
        return future.channel();
    }

    private static void awaitChannelFuture(ChannelFuture future, String message) throws IOException {
        awaitChannelFuture(future, message, P2pConstants.QUIC_CONNECT_TIMEOUT_MS);
    }

    private static void awaitChannelFuture(ChannelFuture future, String message, int timeoutMs) throws IOException {
        if (!future.awaitUninterruptibly(timeoutMs)) {
            future.cancel(false);
            throw new IOException(message + ": timeout");
        }
        if (!future.isSuccess()) {
            throw new IOException(message + ": " + future.cause().getMessage(), future.cause());
        }
    }

    private static <T> T awaitFuture(Future<T> future, String message) throws IOException {
        return awaitFuture(future, message, P2pConstants.QUIC_CONNECT_TIMEOUT_MS);
    }

    private static <T> T awaitFuture(Future<T> future, String message, int timeoutMs) throws IOException {
        if (!future.awaitUninterruptibly(timeoutMs)) {
            future.cancel(false);
            throw new IOException(message + ": timeout");
        }
        if (!future.isSuccess()) {
            throw new IOException(message + ": " + future.cause().getMessage(), future.cause());
        }
        return future.getNow();
    }

    private static void awaitFuture(CompletableFuture<Void> future, String message) throws IOException {
        awaitFuture(future, message, P2pConstants.QUIC_CONNECT_TIMEOUT_MS);
    }

    private static void awaitFuture(CompletableFuture<Void> future, String message, int timeoutMs) throws IOException {
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new IOException(message, exception);
        }
    }

    private static void closeQuietly(Channel channel) {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
    }

    private static void closeSocketQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeServerSocketQuietly(ServerSocket serverSocket) {
        if (serverSocket == null) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private static ByteBuf handshakePayload(int tunnelToken) {
        ByteBuf payload = Unpooled.buffer(HANDSHAKE_SIZE);
        payload.writeBytes(HANDSHAKE_MAGIC);
        payload.writeByte(Byte.toUnsignedInt(P2pConstants.PROTOCOL_VERSION));
        payload.writeInt(tunnelToken);
        return payload;
    }

    private static boolean validHandshake(ByteBuf buffer, int expectedToken) {
        if (buffer.readableBytes() < HANDSHAKE_SIZE) {
            return false;
        }
        for (int index = 0; index < HANDSHAKE_MAGIC.length; index++) {
            if (buffer.getByte(index) != HANDSHAKE_MAGIC[index]) {
                return false;
            }
        }
        if (buffer.getUnsignedByte(HANDSHAKE_MAGIC.length) != Byte.toUnsignedInt(P2pConstants.PROTOCOL_VERSION)) {
            return false;
        }
        return buffer.getInt(HANDSHAKE_MAGIC.length + 1) == expectedToken;
    }

    private static boolean matchesAck(ByteBuf buffer) {
        if (buffer.readableBytes() < HANDSHAKE_ACK.length) {
            return false;
        }
        for (int index = 0; index < HANDSHAKE_ACK.length; index++) {
            if (buffer.getByte(index) != HANDSHAKE_ACK[index]) {
                return false;
            }
        }
        return true;
    }

    private static Socket openTcpSocket(InetAddress targetAddress, int tcpPort) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(targetAddress, tcpPort), P2pConstants.DIRECT_TCP_CONNECT_TIMEOUT_MS);
        return socket;
    }

    private static final class NettyQuicHostSession implements P2pQuicHostSession {
        private final Logger logger;
        private final NioEventLoopGroup group;
        private final Channel channel;
        private final String encodedCertificate;
        private final int port;

        private NettyQuicHostSession(Logger logger, NioEventLoopGroup group, Channel channel,
                                     String encodedCertificate, int port) {
            this.logger = logger;
            this.group = group;
            this.channel = channel;
            this.encodedCertificate = encodedCertificate;
            this.port = port;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public String mode() {
            return "direct";
        }

        @Override
        public String certificate() {
            return encodedCertificate;
        }

        @Override
        public void punch(InetSocketAddress remoteAddress) {
            if (remoteAddress == null || remoteAddress.isUnresolved() || !channel.isActive()) {
                return;
            }

            try {
                channel.writeAndFlush(new DatagramPacket(Unpooled.copiedBuffer(QUIC_PUNCH_PAYLOAD), remoteAddress))
                    .syncUninterruptibly();
                logger.debug("Safra QUIC host punched {}", remoteAddress);
            } catch (RuntimeException exception) {
                logger.debug("Safra QUIC host punch failed for {}: {}", remoteAddress, exception.toString());
            }
        }

        @Override
        public void close() {
            closeQuietly(channel);
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static final class ServerHandshakeHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final Logger logger;
        private final QuicStreamChannel channel;
        private final InetAddress targetAddress;
        private final int tcpPort;
        private final int expectedToken;
        private final ByteBuf handshakeBuffer = Unpooled.buffer(HANDSHAKE_SIZE);

        private ServerHandshakeHandler(Logger logger, QuicStreamChannel channel, InetAddress targetAddress, int tcpPort, int expectedToken) {
            this.logger = logger;
            this.channel = channel;
            this.targetAddress = targetAddress;
            this.tcpPort = tcpPort;
            this.expectedToken = expectedToken;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) throws Exception {
            int missingBytes = HANDSHAKE_SIZE - handshakeBuffer.readableBytes();
            int copyLength = Math.min(missingBytes, message.readableBytes());
            handshakeBuffer.writeBytes(message, copyLength);
            if (handshakeBuffer.readableBytes() < HANDSHAKE_SIZE) {
                return;
            }

            if (!validHandshake(handshakeBuffer, expectedToken)) {
                logger.warn("Safra QUIC auth rejected for {}", channel.remoteAddress());
                context.close();
                return;
            }

            context.pipeline().remove(this);
            context.writeAndFlush(Unpooled.wrappedBuffer(HANDSHAKE_ACK));
            Socket tcpSocket = openTcpSocket(targetAddress, tcpPort);
            P2pSockets.tune(tcpSocket);
            NettyQuicTcpBridge.attach(logger, channel, tcpSocket);
            if (message.isReadable()) {
                context.fireChannelRead(message.readRetainedSlice(message.readableBytes()));
            }
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext context) {
            handshakeBuffer.release();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            logger.debug("Safra QUIC host auth failed: {}", cause.toString());
            context.close();
        }
    }

    private static final class ClientHandshakeAckHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final ByteBuf ackBuffer = Unpooled.buffer(HANDSHAKE_ACK.length);
        private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
            int missingBytes = HANDSHAKE_ACK.length - ackBuffer.readableBytes();
            int copyLength = Math.min(missingBytes, message.readableBytes());
            ackBuffer.writeBytes(message, copyLength);
            if (ackBuffer.readableBytes() < HANDSHAKE_ACK.length) {
                return;
            }

            if (!matchesAck(ackBuffer)) {
                readyFuture.completeExceptionally(new IOException("Safra QUIC auth ack is invalid"));
                context.close();
                return;
            }

            readyFuture.complete(null);
            context.pipeline().remove(this);
            if (message.isReadable()) {
                context.fireChannelRead(message.readRetainedSlice(message.readableBytes()));
            }
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext context) {
            ackBuffer.release();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            readyFuture.completeExceptionally(cause);
            context.close();
        }

        private void await(int timeoutMs) throws IOException {
            awaitFuture(readyFuture, "Safra QUIC auth failed", timeoutMs);
        }
    }

    private static final class NettyQuicTcpBridge extends SimpleChannelInboundHandler<ByteBuf> {
        private final Logger logger;
        private final QuicStreamChannel streamChannel;
        private final Socket socket;
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final BlockingQueue<byte[]> inboundQueue = new LinkedBlockingQueue<>(P2pConstants.QUIC_BRIDGE_QUEUE_CAPACITY);

        private NettyQuicTcpBridge(Logger logger, QuicStreamChannel streamChannel, Socket socket) {
            this.logger = logger;
            this.streamChannel = streamChannel;
            this.socket = socket;
        }

        static NettyQuicTcpBridge attach(Logger logger, QuicStreamChannel streamChannel, Socket socket) {
            NettyQuicTcpBridge bridge = new NettyQuicTcpBridge(logger, streamChannel, socket);
            streamChannel.pipeline().addLast(bridge);
            bridge.startInboundPump();
            bridge.startOutboundPump();
            return bridge;
        }

        void await() throws IOException {
            try {
                closedLatch.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Safra QUIC bridge interrupted", exception);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) throws Exception {
            byte[] bytes = new byte[message.readableBytes()];
            message.readBytes(bytes);
            if (!inboundQueue.offer(bytes)) {
                logger.warn("Safra QUIC bridge inbound queue overflow for {}", streamChannel.remoteAddress());
                context.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            if (!closed.get()) {
                logger.debug("Safra QUIC stream failed: {}", cause.toString());
            }
            close();
        }

        private void startOutboundPump() {
            P2pRuntime.start("safra-quic-bridge-outbound", () -> {
                byte[] buffer = new byte[P2pConstants.QUIC_BRIDGE_BUFFER_SIZE];
                try (InputStream input = socket.getInputStream()) {
                    int pendingBytes = 0;
                    ChannelFuture lastWrite = null;
                    while (!closed.get()) {
                        int read = input.read(buffer);
                        if (read < 0) {
                            break;
                        }
                        ByteBuf byteBuf = Unpooled.copiedBuffer(buffer, 0, read);
                        lastWrite = streamChannel.write(byteBuf);
                        pendingBytes += read;
                        if (pendingBytes >= P2pConstants.QUIC_BRIDGE_FLUSH_THRESHOLD_BYTES || input.available() == 0) {
                            streamChannel.flush();
                            if (lastWrite != null) {
                                lastWrite.syncUninterruptibly();
                            }
                            pendingBytes = 0;
                        }
                    }
                    if (lastWrite != null && pendingBytes > 0) {
                        streamChannel.flush();
                        lastWrite.syncUninterruptibly();
                    }
                } catch (IOException exception) {
                    if (!closed.get()) {
                        logger.debug("Safra QUIC outbound pump stopped: {}", exception.toString());
                    }
                } finally {
                    if (streamChannel.isActive()) {
                        streamChannel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
                    }
                    close();
                }
            });
        }

        private void startInboundPump() {
            P2pRuntime.start("safra-quic-bridge-inbound", () -> {
                try (OutputStream output = new BufferedOutputStream(
                    socket.getOutputStream(),
                    P2pConstants.QUIC_BRIDGE_BUFFER_SIZE
                )) {
                    int pendingBytes = 0;
                    while (!closed.get()) {
                        byte[] bytes = inboundQueue.poll(500L, TimeUnit.MILLISECONDS);
                        if (bytes == null) {
                            if (pendingBytes > 0) {
                                output.flush();
                                pendingBytes = 0;
                            }
                            continue;
                        }
                        output.write(bytes);
                        pendingBytes += bytes.length;
                        if (pendingBytes >= P2pConstants.QUIC_BRIDGE_FLUSH_THRESHOLD_BYTES || inboundQueue.isEmpty()) {
                            output.flush();
                            pendingBytes = 0;
                        }
                    }
                    output.flush();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (IOException exception) {
                    if (!closed.get()) {
                        logger.debug("Safra QUIC inbound pump stopped: {}", exception.toString());
                    }
                } finally {
                    close();
                }
            });
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            try {
                socket.close();
            } catch (IOException ignored) {
            }
            inboundQueue.clear();
            if (streamChannel.isOpen()) {
                streamChannel.close().syncUninterruptibly();
            }
            closedLatch.countDown();
        }
    }
}
