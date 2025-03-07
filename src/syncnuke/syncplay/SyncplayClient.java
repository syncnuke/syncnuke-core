package syncnuke.syncplay;

import lombok.extern.slf4j.Slf4j;
import syncnuke.tcp.TcpClient;

@Slf4j
public class SyncplayClient extends TcpClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    public SyncplayClient() {
        super(SERVER_HOST, SERVER_PORT);
    }

}
