package org.developerkubilay.safra.p2p.turn;

import java.util.List;

public record P2pTurnCredentials(
    List<TurnServer> udpServers,
    List<TurnServer> tcpServers,
    List<TurnServer> tlsServers,
    String username,
    String credential,
    int ttlSeconds
) {
    public record TurnServer(String host, int port) {
    }
}
