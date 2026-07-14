package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import org.developerkubilay.safra.p2p.P2pClientProxy;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.P2pHostService;
import org.developerkubilay.safra.p2p.P2pHostSupport;
import org.developerkubilay.safra.p2p.P2pRuntime;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class P2pManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pManager.class);
    private static final P2pManager INSTANCE = new P2pManager();
    private static final Executor BACKGROUND_EXECUTOR = command -> P2pRuntime.start("safra-p2p-background", command);

    private volatile P2pHostService hostService;
    private volatile P2pHostService startingHostService;
    private volatile CompletableFuture<P2pShareCode> hostStartFuture;
    private volatile P2pClientProxy startingClientProxy;
    private volatile P2pClientProxy activeClientProxy;
    private volatile CompletableFuture<RewriteResult> rewriteFuture;
    private boolean pendingClientFailureContext;
    private boolean pendingDirectShareFailureContext;
    private long hostStartGeneration;
    private long rewriteGeneration;

    private P2pManager() {
    }

    public static P2pManager getInstance() {
        return INSTANCE;
    }

    public synchronized CompletableFuture<P2pShareCode> startHostingAsync(int tcpPort) {
        return startHostingAsync(tcpPort, null);
    }

    public synchronized CompletableFuture<P2pShareCode> startHostingAsync(int tcpPort, String fixedCode) {
        stopHosting();

        String rendezvousCode = P2pConstants.useApi30Rendezvous()
            ? P2pHostSupport.resolvePreferredRendezvousCode(fixedCode)
            : P2pShareCode.normalizeRendezvousCode(fixedCode);
        int token = P2pConstants.useApi30Rendezvous()
            ? P2pHostSupport.createRendezvousShareToken(rendezvousCode)
            : P2pHostSupport.createShareToken();
        P2pHostService service = new P2pHostService(tcpPort, token, rendezvousCode);
        long generation = ++hostStartGeneration;
        startingHostService = service;

        CompletableFuture<P2pShareCode> future = CompletableFuture.supplyAsync(() -> {
            try {
                P2pShareCode shareCode = service.start();
                synchronized (P2pManager.this) {
                    if (hostStartGeneration != generation || startingHostService != service) {
                        service.close();
                        throw new CancellationException("Safra P2P host start was replaced");
                    }

                    startingHostService = null;
                    hostService = service;
                    return shareCode;
                }
            } catch (IOException exception) {
                service.close();
                synchronized (P2pManager.this) {
                    if (startingHostService == service) {
                        startingHostService = null;
                    }
                }
                throw new CompletionException(exception);
            } catch (RuntimeException exception) {
                service.close();
                synchronized (P2pManager.this) {
                    if (startingHostService == service) {
                        startingHostService = null;
                    }
                }
                throw exception;
            }
        }, BACKGROUND_EXECUTOR);

        hostStartFuture = future;
        return future;
    }

    public synchronized void stopHosting() {
        hostStartGeneration++;
        P2pHostService starting = startingHostService;
        startingHostService = null;
        if (starting != null) {
            LOGGER.info("Safra P2P pending host service stopping");
            starting.close();
        }

        CompletableFuture<P2pShareCode> pendingFuture = hostStartFuture;
        hostStartFuture = null;
        if (pendingFuture != null) {
            pendingFuture.cancel(false);
        }

        if (hostService != null) {
            LOGGER.info("Safra P2P host service stopping");
            hostService.close();
            hostService = null;
        }
    }

    public synchronized RewriteResult createRewrite(ServerData originalServerInfo) throws IOException {
        long generation = ++rewriteGeneration;
        cancelPendingRewriteInternal();
        return createRewrite(originalServerInfo, generation);
    }

    public CompletableFuture<RewriteResult> createRewriteAsync(ServerData originalServerInfo) {
        Objects.requireNonNull(originalServerInfo, "originalServerInfo");
        ServerData snapshot = ForgeVersionCompat.copyServerData(originalServerInfo, ForgeVersionCompat.getServerAddress(originalServerInfo));

        long generation;
        synchronized (this) {
            generation = ++rewriteGeneration;
            cancelPendingRewriteInternal();
        }

        CompletableFuture<RewriteResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return createRewrite(snapshot, generation);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, BACKGROUND_EXECUTOR);

        synchronized (this) {
            rewriteFuture = future;
        }
        return future;
    }

    public synchronized void cancelPendingRewrite() {
        rewriteGeneration++;
        cancelPendingRewriteInternal();
    }

    private RewriteResult createRewrite(ServerData originalServerInfo, long generation) throws IOException {
        Objects.requireNonNull(originalServerInfo, "originalServerInfo");
        String originalAddress = ForgeVersionCompat.getServerAddress(originalServerInfo);
        P2pShareCode shareCode = P2pShareCode.parse(originalAddress);

        P2pClientProxy[] proxyRef = new P2pClientProxy[1];
        P2pClientProxy proxy = new P2pClientProxy(shareCode, () -> {
            synchronized (P2pManager.this) {
                if (activeClientProxy == proxyRef[0]) {
                    activeClientProxy = null;
                }
                if (startingClientProxy == proxyRef[0]) {
                    startingClientProxy = null;
                }
            }
        });
        proxyRef[0] = proxy;
        synchronized (this) {
            if (rewriteGeneration != generation) {
                throw new CancellationException("Safra P2P connection prepare was canceled");
            }
            pendingClientFailureContext = false;
            pendingDirectShareFailureContext = false;
            startingClientProxy = proxy;
        }
        int localPort;
        try {
            localPort = proxy.start();
        } catch (IOException exception) {
            proxy.close();
            synchronized (this) {
                if (startingClientProxy == proxy) {
                    startingClientProxy = null;
                }
            }
            throw exception;
        }

        synchronized (this) {
            if (rewriteGeneration != generation || startingClientProxy != proxy) {
                proxy.close();
                throw new CancellationException("Safra P2P connection prepare was canceled");
            }

            startingClientProxy = null;
            activeClientProxy = proxy;
            rewriteFuture = null;
            pendingClientFailureContext = true;
            pendingDirectShareFailureContext = !shareCode.isRendezvous();
        }
        String localAddress = P2pConstants.LOCAL_PROXY_HOST + ":" + localPort;
        ServerData rewritten = ForgeVersionCompat.copyServerData(originalServerInfo, localAddress);
        return new RewriteResult(ForgeVersionCompat.parseServerAddress(ForgeVersionCompat.getServerAddress(rewritten)), rewritten);
    }

    public synchronized void shutdown() {
        stopHosting();
        cancelPendingRewriteInternal();
        pendingClientFailureContext = false;
        pendingDirectShareFailureContext = false;
    }

    public synchronized void startBedrockRelay(Consumer<String> readyHandler, Runnable unavailableHandler) {
        if (hostService != null) {
            hostService.startBedrockRelay(readyHandler, unavailableHandler);
        }
    }

    public void tick(Minecraft client) {
        P2pHostService service = hostService;
        if (service == null) {
            return;
        }

        IntegratedServer server = ForgeLanGameRules.getSingleplayerServer(client);
        if (server == null) {
            stopHosting();
            return;
        }

        int currentPort = ForgeLanGameRules.getServerPort(server);
        if (currentPort < 1024 || currentPort > 65535) {
            return;
        }
        if (currentPort != service.tcpPort()) {
            LOGGER.info("Safra P2P host service stopping because LAN port changed from {} to {}", service.tcpPort(), currentPort);
            stopHosting();
        }
    }

    public static boolean isP2pStoredAddress(String address) {
        return P2pShareCode.isStoredAddress(address);
    }

    public static boolean isLikelyP2pAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }

        if (isP2pStoredAddress(address)) {
            return true;
        }

        return org.developerkubilay.safra.p2p.P2pShareCode.normalizeRendezvousCode(address) != null;
    }

    public static boolean isValidP2pAddress(String address) {
        try {
            P2pShareCode.parse(address);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static String toStoredAddress(String address) {
        return P2pShareCode.parse(address).toStoredAddress();
    }

    public static String toDisplayAddress(String address) {
        return P2pShareCode.parse(address).toDisplayCode();
    }

    private boolean activeClientUsesDirectShareAddress() {
        return activeClientProxy != null && !activeClientProxy.usesRendezvousShareCode();
    }

    public synchronized ClientFailureContext consumeClientFailureContext() {
        boolean p2p = pendingClientFailureContext || activeClientProxy != null;
        boolean direct = pendingDirectShareFailureContext || activeClientUsesDirectShareAddress();
        pendingClientFailureContext = false;
        pendingDirectShareFailureContext = false;
        return new ClientFailureContext(p2p, direct);
    }

    private void cancelPendingRewriteInternal() {
        P2pClientProxy starting = startingClientProxy;
        startingClientProxy = null;
        if (starting != null) {
            starting.close();
        }

        CompletableFuture<RewriteResult> pendingFuture = rewriteFuture;
        rewriteFuture = null;
        if (pendingFuture != null) {
            pendingFuture.cancel(false);
        }

        if (activeClientProxy != null) {
            activeClientProxy.close();
            activeClientProxy = null;
        }
    }

    public record RewriteResult(ServerAddress serverAddress, ServerData serverInfo) {
    }

    public record ClientFailureContext(boolean p2p, boolean directShareAddress) {
    }
}
