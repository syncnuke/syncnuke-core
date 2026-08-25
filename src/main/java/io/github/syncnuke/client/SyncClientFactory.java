package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.protocol.datasaver.DataSaverClient;
import io.github.syncnuke.client.internal.protocol.syncplay.SyncplayClient;
import io.github.syncnuke.player.PlayerManager;

/**
 * Factory for creating SyncClient instances based on the specified protocol.
 */
class SyncClientFactory {

    /**
     * Creates a SyncClient instance based on the specified protocol.
     *
     * @param protocol    the protocol to use (e.g., "syncplay", "datasaver")
     * @param host        the host address to connect to
     * @param port        the port to connect on
     * @param videoPlayer the controlled video player instance to use with the client
     * @return a SyncClient implementation for the requested protocol
     * @throws IllegalArgumentException if the protocol is not supported
     */
    public static SyncClient<?> createClient(String protocol, String host, int port, PlayerManager videoPlayer) {
        switch (protocol.toLowerCase()) {
            case "syncplay":
                return new SyncplayClient(host, port, videoPlayer);
            case "datasaver":
                return new DataSaverClient(host, port, videoPlayer);
            default:
                throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        }
    }

}
