package syncnuke.syncplay;

import org.slf4j.Logger;
import syncnuke.sync.MpvSyncClient;
import syncnuke.syncplay.player.MpvPlayer;
import syncnuke.syncplay.player.VideoPlayer;
import syncnuke.tcp.DataProcessor;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import static org.slf4j.LoggerFactory.getLogger;

public class Main {

    private static final Logger logger = getLogger(Main.class);
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    public static void main(String[] args) throws InterruptedException {
//        DataProcessor dataProcessor = new DataProcessor();
        CountDownLatch latch = new CountDownLatch(1);

        // flatpak run --filesystem=~/.mpv-ipc io.mpv.Mpv --player-operation-mode=pseudo-gui --input-ipc-server=$HOME/.mpv-ipc/mpvsocket
        String socketPath = System.getProperty("user.home") + "/.mpv-ipc/mpvsocket";
        try (VideoPlayer videoPlayer = new MpvPlayer(socketPath);
                MpvSyncClient client = new MpvSyncClient(SERVER_HOST, SERVER_PORT, videoPlayer)) {
//            client.login("user", "room");

            videoPlayer.load(args[0]);

            // Wait for client to close before terminating
            Runtime.getRuntime().addShutdownHook(new Thread(client::close));
            latch.await();
        } catch (IOException exception) {
            logger.error("Error initializing MPV player: {}", exception.getMessage());
        } catch (Exception e) {
            logger.error("An unexpected error occurred: {}", e.getMessage());
        } finally {
            latch.countDown();
        }
    }

}
