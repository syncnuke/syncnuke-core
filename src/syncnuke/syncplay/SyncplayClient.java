package syncnuke.syncplay;

import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.commands.BaseCommand;
import syncnuke.syncplay.data.HelloData;
import syncnuke.tcp.TcpClient;

import java.util.concurrent.TimeUnit;

@Slf4j
public class SyncplayClient extends TcpClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    private boolean loggedIn = false;

    public SyncplayClient() {
        super(SERVER_HOST, SERVER_PORT);
    }

    private void logout() {
        if (loggedIn) {
            getScheduler().shutdown();
        }
    }

    public void login(String username, String room) {
        logout();
        BaseCommand command = new BaseCommand(this);
        command.execute(new HelloData(username, room));
        loggedIn = true;
    }
}
