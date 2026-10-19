package io.github.syncnuke.client.internal.protocol.syncplay.net;

import io.github.syncnuke.client.internal.net.Codec;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.net.NetListener;
import lombok.extern.slf4j.Slf4j;
import pl.syncplay.proto.SyncplayProto.*;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

@Slf4j
public final class SyncplayTransport implements NetClient<SyncplayMessage> {

    private final int timeoutMillis;
    private final SSLSocketFactory tlsFactory;
    private volatile Socket socket;
    private Codec<SyncplayMessage> codec;
    private volatile boolean closed;
    private final Set<NetListener<SyncplayMessage>> listeners = new CopyOnWriteArraySet<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "syncplay-tcp");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean ready;
    private volatile boolean authenticated;
    private int failures; // Owned by the reader thread; reset after authentication.
    private Runnable connected = () -> {
    };
    private Runnable disconnected = () -> {
    };
    private Runnable fatal = () -> {
    };

    public SyncplayTransport() {
        this((SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    public SyncplayTransport(SSLSocketFactory tlsFactory) {
        this(tlsFactory, 12500);
    }

    public SyncplayTransport(SSLSocketFactory tlsFactory, int timeoutMillis) {
        this.tlsFactory = tlsFactory;
        this.timeoutMillis = timeoutMillis;
    }

    public void callbacks(Runnable connected, Runnable disconnected, Runnable fatal) {
        this.connected = connected;
        this.disconnected = disconnected;
        this.fatal = fatal;
    }

    @Override
    public void connect(String host, int port, Codec<SyncplayMessage> codec) {
        this.codec = codec;
        reader.execute(() -> run(host, port));
    }

    private Socket negotiate(String host, int port) throws IOException {
        Socket tcp = openSocket(host, port);
        SyncplayMessage reply = requestTls(tcp);
        if (reply.hasTls()) {
            return switch (reply.getTls().getStartTls()) {
                case "true" -> upgradeToTls(tcp, host, port, tlsFactory);
                case "false" -> tcp;
                default -> throw new IOException("Unexpected STARTTLS reply");
            };
        }
        if (reply.hasError() && reply.getError().getMessage().contains("startTLS")) {
            // Older servers reject STARTTLS; reconnect once without negotiation.
            closeSocket();
            return openSocket(host, port);
        }
        throw new IOException("Unexpected STARTTLS reply");
    }

    private static SSLSocket upgradeToTls(Socket tcp, String host, int port,
                                          SSLSocketFactory tlsFactory) throws IOException {
        SSLSocket tls = (SSLSocket) tlsFactory.createSocket(tcp, host, port, true);
        try {
            verifyHostname(tls);
            tls.setSoTimeout(tcp.getSoTimeout());
            tls.startHandshake();
            return tls;
        } catch (IOException | RuntimeException e) {
            try {
                tls.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static void verifyHostname(SSLSocket socket) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
    }

    private SyncplayMessage requestTls(Socket tcp) throws IOException {
        SyncplayMessage request = SyncplayMessage.newBuilder()
                .setTls(TLSMessage.newBuilder().setStartTls("send")).build();
        tcp.getOutputStream().write(codec.encode(request));
        // No read-ahead: bytes following this reply may already be TLS records.
        return decodeUntil(tcp, tcp.getInputStream(), deadline());
    }

    private boolean isAuthenticated(SyncplayMessage message) {
        if (message.hasHello()) {
            return true;
        }
        for (SyncplayMessage part : message.getCommandsList()) {
            if (part.hasHello()) {
                return true;
            }
        }
        return false;
    }

    private boolean refreshesDeadline(SyncplayMessage message) {
        if (message.hasState() && message.getState().getPlaystate().hasPaused()) {
            return true;
        }
        for (SyncplayMessage part : message.getCommandsList()) {
            if (part.hasState() && part.getState().getPlaystate().hasPaused()) {
                return true;
            }
        }
        return false;
    }

    private void run(String host, int port) {
        failures = 0;
        while (!closed) {
            try {
                runSession(host, port);
            } catch (SSLHandshakeException invalidCertificate) {
                log.error("Failed to connect to server {}:{}", host, port, invalidCertificate);
                fatal.run(); // A rejected certificate must never fall back to plaintext.
                return;
            } catch (Exception failure) {
                if (closed) {
                    return;
                }
                log.error("Connection failed: {}", failure.getMessage(), failure);
            } finally {
                ready = authenticated = false;
                closeSocket();
            }
            if (!retry()) {
                return;
            }
        }
    }

    private void runSession(String host, int port) throws IOException {
        socket = negotiate(host, port);
        if (closed) {
            return;
        }
        ready = true;
        log.debug("Connected to {}:{} over TCP", host, port);
        connected.run();
        listen();
    }

    private void listen() throws IOException {
        InputStream input = new BufferedInputStream(socket.getInputStream());
        long readDeadline = deadline();
        while (!closed) {
            SyncplayMessage message = decodeUntil(socket, input, readDeadline);
            if (isAuthenticated(message)) {
                failures = 0;
                authenticated = true;
            }
            if (refreshesDeadline(message)) {
                readDeadline = deadline();
            }
            notifyListeners(message);
        }
    }

    private boolean retry() {
        if (closed) {
            return false;
        }
        disconnected.run();
        if (failures >= 999) {
            fatal.run();
            return false;
        }
        try {
            Thread.sleep(100L << Math.min(failures++, 5));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private SyncplayMessage decodeUntil(Socket connection, InputStream input, long deadline) throws IOException {
        return codec.decode(new FilterInputStream(input) {
            @Override
            public int read() throws IOException {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new SocketTimeoutException("Syncplay State timeout");
                }
                // Buffered bytes need no socket option change. A trickling partial frame
                // still has the same deadline as the complete message we are waiting for.
                if (in.available() == 0) {
                    connection.setSoTimeout((int) Math.max(1,
                            TimeUnit.NANOSECONDS.toMillis(remaining)));
                }
                return in.read();
            }
        });
    }

    @Override
    public synchronized void send(SyncplayMessage message) {
        if (!ready || closed || (!authenticated && !message.hasHello())) {
            return;
        }
        byte[] bytes;
        try {
            bytes = codec.encode(message);
        } catch (IOException invalidCommand) {
            log.error("Failed to send data: {}", invalidCommand.getMessage());
            fatal.run(); // Invalid local configuration cannot be repaired by reconnecting.
            return;
        }
        try {
            log.debug("Sending: {}", message.getBodyCase());
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        } catch (IOException failure) {
            log.error("Failed to send data: {}", failure.getMessage());
            ready = authenticated = false;
            closeSocket(); // Reader owns recovery, not SyncClient's fatal-send path.
        }
    }

    private Socket openSocket(String host, int port) throws IOException {
        Socket tcp = new Socket();
        socket = tcp; // close() also interrupts an in-progress connect.
        if (closed) {
            tcp.close();
            throw new IOException("Client is closed");
        }
        tcp.connect(new InetSocketAddress(host, port), 5000);
        tcp.setTcpNoDelay(true);
        tcp.setSoTimeout(timeoutMillis);
        return tcp;
    }

    private void closeSocket() {
        Socket current = socket;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (IOException e) {
            log.error("Failed to close connection: {}", e.getMessage());
        }
    }

    private void notifyListeners(SyncplayMessage message) {
        for (NetListener<SyncplayMessage> listener : listeners) {
            try {
                listener.onResponse(message);
            } catch (Exception e) {
                log.error("Error in listener: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void addListener(NetListener<SyncplayMessage> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public boolean removeListener(NetListener<SyncplayMessage> listener) {
        return listener != null && listeners.remove(listener);
    }

    @Override
    public void close() {
        ready = authenticated = false;
        closed = true;
        closeSocket();
        reader.shutdownNow();
        listeners.clear();
    }

}
