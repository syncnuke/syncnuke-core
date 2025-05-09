package syncnuke.syncplay;

import org.slf4j.Logger;
import syncnuke.syncplay.player.MpvPlayer;
import syncnuke.syncplay.player.VideoPlayer;
import syncnuke.tcp.DataProcessor;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import static org.slf4j.LoggerFactory.*;

public class Main {

    private static final Logger logger = getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        DataProcessor dataProcessor = new DataProcessor();
        CountDownLatch latch = new CountDownLatch(1);

        try (VideoPlayer videoPlayer = new MpvPlayer("/tmp/mpvsocket");
                SyncplayClient client = new SyncplayClient(dataProcessor, videoPlayer)) {
            client.login("user", "room");

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
