package syncnuke.tcp;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class KeepAliveClient extends TcpClient {

    private final ScheduledExecutorService scheduler;

    public KeepAliveClient(String host, int port) {
        super(host, port);
        scheduler = Executors.newScheduledThreadPool(1);
    }

    protected abstract void keepAlive();

    /**
     * Periodically trigger keep alive function, in order to maintain the connection to the server.
     * @param period the time in seconds between each keep alive.
     */
    public void startKeepAlive(long period) {
        scheduler.scheduleAtFixedRate(this::keepAlive, 0, period, TimeUnit.SECONDS);
    }

    public void stopKeepAlive() {
        scheduler.shutdown();
    }

}
