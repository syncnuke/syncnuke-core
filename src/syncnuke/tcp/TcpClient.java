package syncnuke.tcp;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class TcpClient {

    private final Socket socket;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final ExecutorService executor;

    public TcpClient(String host, int port) {
        try {
            socket = new Socket(host, port);
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            executor = Executors.newSingleThreadExecutor();
            startListening();
        } catch (IOException e) {
            log.error("Failed to connect to server: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // Keep the connection alive
    private void startListening() {
        executor.submit(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("Server response: {}", line);
                    // TODO: handle message from server
                }
            } catch (IOException e) {
                log.error("Connection closed: {}", e.getMessage());
            }
        });
    }

    public void send(String data) {
        try {
            log.info("Sending: {}", data);
            writer.write(data);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to send data: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void close() {
        try {
            socket.close();
            executor.shutdown();
        } catch (IOException e) {
            log.error("Failed to close connection: {}", e.getMessage());
        }
    }

}
