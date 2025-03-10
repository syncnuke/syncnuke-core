package syncnuke.tcp;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public abstract class TcpClient implements Closeable {

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
                    log.debug("Server response: {}", line);
                    handleResponse(line);
                }
            } catch (IOException e) {
                log.error("Connection closed: {}", e.getMessage());
            }
        });
    }

    protected abstract void handleResponse(String line);

    public void send(String data) {
        try {
            log.debug("Sending: {}", data);
            writer.write(data);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to send data: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            log.error("Failed to close connection: {}", e.getMessage());
        }
    }

}
