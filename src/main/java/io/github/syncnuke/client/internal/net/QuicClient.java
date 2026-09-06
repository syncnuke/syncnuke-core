package io.github.syncnuke.client.internal.net;

import lombok.extern.slf4j.Slf4j;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A {@link NetClient} transported over a single bidirectional QUIC stream.
 *
 * <p>The QUIC stream is exposed to the existing {@link Codec} as one ordered
 * byte stream. This keeps transport framing out of protocol codecs.</p>
 */
@Slf4j
public class QuicClient<T> implements NetClient<T> {

    static final String APPLICATION_PROTOCOL = "syncnuke";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private final ExecutorService decoderExecutor = Executors.newSingleThreadExecutor();
    private final Set<NetListener<T>> listeners = new CopyOnWriteArraySet<>();

    private volatile boolean closed;
    private volatile QuicClientConnection connection;
    private Codec<T> codec;
    private volatile InputStream input;
    private OutputStream output;

    @Override
    public synchronized void connect(String host, int port, Codec<T> codec) {
        if (closed) {
            throw new IllegalStateException("QUIC client is closed");
        }

        disconnect();
        this.codec = Objects.requireNonNull(codec, "codec");

        try {
            QuicClientConnection newConnection = QuicClientConnection.newBuilder()
                    .host(host)
                    .port(port)
                    .applicationProtocol(APPLICATION_PROTOCOL)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .maxIdleTimeout(MAX_IDLE_TIMEOUT)
                    .build();
            connection = newConnection;
            newConnection.connect();
            QuicStream stream = newConnection.createStream(true);

            input = stream.getInputStream();
            output = stream.getOutputStream();
            startDecoding(input, codec);
            log.debug("Connected to {}:{} over QUIC", host, port);
        } catch (Exception e) {
            disconnect();
            log.error("Failed to connect to QUIC server {}:{}", host, port, e);
            throw new RuntimeException("Failed to connect to QUIC server", e);
        }
    }

    private void startDecoding(InputStream connectionInput, Codec<T> connectionCodec) {
        decoderExecutor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && connectionInput == input) {
                    T data = connectionCodec.decode(connectionInput);
                    if (data != null) {
                        notifyListeners(data);
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (!closed && connectionInput == input) {
                    log.error("QUIC connection failed while receiving data", e);
                    disconnect(connectionInput);
                }
            }
        });
    }

    private void notifyListeners(T data) {
        for (NetListener<T> listener : listeners) {
            try {
                listener.onResponse(data);
            } catch (RuntimeException e) {
                log.error("Error in QUIC response listener", e);
            }
        }
    }

    @Override
    public void addListener(NetListener<T> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public boolean removeListener(NetListener<T> listener) {
        return listener != null && listeners.remove(listener);
    }

    @Override
    public synchronized void send(T data) {
        if (output == null) {
            throw new IllegalStateException("QUIC stream is not connected");
        }
        try {
            log.debug("Sending: {}", data);
            output.write(codec.encode(data));
            output.flush();
        } catch (IOException e) {
            log.error("Failed to send QUIC data", e);
            throw new RuntimeException("Failed to send QUIC data", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        disconnect();
        decoderExecutor.shutdownNow();
    }

    private synchronized void disconnect(InputStream connectionInput) {
        if (input == connectionInput) {
            disconnect();
        }
    }

    private synchronized void disconnect() {
        InputStream oldInput = input;
        OutputStream oldOutput = output;
        QuicClientConnection oldConnection = connection;
        input = null;
        output = null;
        connection = null;

        closeQuietly(oldInput);
        closeQuietly(oldOutput);
        if (oldConnection != null) {
            oldConnection.close();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The connection is already being torn down.
        }
    }

}
