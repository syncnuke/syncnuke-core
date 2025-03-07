package syncnuke.syncplay;

import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.commands.BaseCommand;
import syncnuke.syncplay.data.HelloData;
import syncnuke.syncplay.data.StateData;
import syncnuke.tcp.TcpClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SyncplayClient extends TcpClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    private final ScheduledExecutorService scheduler;
    private boolean loggedIn = false;

    public SyncplayClient() {
        super(SERVER_HOST, SERVER_PORT);
        scheduler = Executors.newScheduledThreadPool(1);
    }

    private void logout() {
        if (loggedIn) {
            scheduler.shutdown();
        }
    }

    public void login(String username, String room) {
        logout();
        BaseCommand command = new BaseCommand(this);
        command.execute(new HelloData(username, room));
        loggedIn = true;
        startStateUpdates();
    }

    @Override
    protected void handleResponse(String line) {
        log.debug("Server response: {}", line);
    }

    private void keepAlive() {
        StateData stateData = new StateData(0, true, false);
        BaseCommand stateCommand = new BaseCommand(this);
        stateCommand.execute(stateData);
    }

    // Send state updates every 5 seconds
    private void startStateUpdates() {
        scheduler.scheduleAtFixedRate(this::keepAlive, 0, 5, TimeUnit.SECONDS);
    }

}
