package syncnuke.syncplay;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;

@Slf4j
public class SyncplayClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    private final Socket socket;
    private final BufferedWriter writer;
    private final BufferedReader reader;

    public SyncplayClient() {
        try {
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (IOException e) {
            log.atError().log("Failed to connect to server: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void send(String data) {
        try {
            writer.write(data);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            log.atInfo().log("Server response: {}", response);

        } catch (IOException e) {
            log.atError().log("Failed to send data: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

}
