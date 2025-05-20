package syncnuke.client;

import syncnuke.client.datasaver.SlimSyncClient;
import syncnuke.client.syncplay.SyncplayClient;
import syncnuke.player.VideoPlayer;

/**
 * Factory for creating SyncClient instances based on the specified protocol.
 */
public class SyncClientFactory {

    /**
     * Creates a SyncClient instance based on the specified protocol.
     *
     * @param protocol    the protocol to use (e.g., "syncplay", "datasaver")
     * @param host        the host address to connect to
     * @param port        the port to connect on
     * @param videoPlayer the video player instance to use with the client
     * @return a SyncClient implementation for the requested protocol
     * @throws IllegalArgumentException if the protocol is not supported
     */
    public static SyncClient createClient(String protocol, String host, int port, VideoPlayer videoPlayer) {
        return switch (protocol.toLowerCase()) {
            case "syncplay" -> new SyncplayClient(host, port, videoPlayer);
            case "datasaver" -> new SlimSyncClient(host, port, videoPlayer);
            default -> throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        };
    }

}
