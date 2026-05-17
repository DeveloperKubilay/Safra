package org.developerkubilay.safra.p2p;

public final class P2pQuicProbeMain {
    private P2pQuicProbeMain() {
    }

    public static void main(String[] args) {
        try {
            NettyQuicSupport.probeRuntimeAvailability();
            System.out.println("Safra QUIC probe passed");
            System.exit(0);
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            System.exit(2);
        }
    }
}
