package syncnuke.tcp;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public abstract class TcpClient implements Closeable {

    private Socket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final ExecutorService executor;

    public TcpClient(String host, int port) {
        try {
            connect(host, port);
            executor = Executors.newSingleThreadExecutor();
            startListening();
        } catch (IOException e) {
            log.error("Failed to connect to server: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
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
            } catch (EOFException e) {
                log.info("Server closed connection");
            } catch (IOException e) {
                log.error("Connection lost: {}", e.getMessage());
            }
        });
    }

    protected abstract void handleResponse(String line);

    public synchronized void send(String data) {
        try {
            if (socket == null || socket.isClosed() || !socket.isConnected()) {
                log.error("Socket is not connected. Attempting to reconnect...");
                reconnect();
            }
            log.debug("Sending: {}", data);
            writer.write(data);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to send data: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void reconnect() {
        try {
            close();
            connect(socket.getInetAddress().getHostName(), socket.getPort());
            log.info("Reconnected to the server.");
        } catch (IOException e) {
            log.error("Failed to reconnect to the server: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            log.error("Failed to close connection: {}", e.getMessage());
        }
    }

}
