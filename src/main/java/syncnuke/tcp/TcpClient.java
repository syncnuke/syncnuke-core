package syncnuke.tcp;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public abstract class TcpClient<T> implements Closeable {

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final Codec<T> codec;
    private final ExecutorService executor;

    public TcpClient(String host, int port, Codec<T> codec) {
        this.codec = codec;
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
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    // Keep the connection alive
    private void startListening() {
        executor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    T data = codec.decode(in);
                    if (data != null) {
                        handleResponse(data);
                    }
                }
            } catch (IOException e) {
                log.error("Connection lost: {}", e.getMessage());
            }
        });
    }

    protected abstract void handleResponse(T data);

    public synchronized void send(T data) {
        try {
            if (socket == null || socket.isClosed() || !socket.isConnected()) {
                log.error("Socket is not connected. Attempting to reconnect...");
                reconnect();
            }
            log.debug("Sending: {}", data);
            out.write(codec.encode(data));
        } catch (IOException e) {
            log.error("Failed to send data: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void reconnect() {
        try {
            close();
            connect(socket.getInetAddress().getHostName(), socket.getPort());
            log.info("Reconnected to TCP Server.");
        } catch (IOException e) {
            log.error("Failed to reconnect: {}", e.getMessage());
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
