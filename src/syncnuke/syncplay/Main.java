package syncnuke.syncplay;

import java.util.concurrent.CountDownLatch;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        try (SyncplayClient client = new SyncplayClient()) {
            client.login("user", "room");

            // Wait for client to close before terminating
            Runtime.getRuntime().addShutdownHook(new Thread(client::close));
            latch.await();
        }
    }

}
