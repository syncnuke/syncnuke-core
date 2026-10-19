package io.github.syncnuke.client.internal.protocol.syncplay;

import io.github.syncnuke.client.internal.protocol.syncplay.data.SyncplayCodec;
import io.github.syncnuke.client.internal.protocol.syncplay.net.SyncplayTransport;

import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.player.internal.PlayerManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.KeyStore;
import pl.syncplay.proto.SyncplayProto.*;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.syncnuke.client.ClientTestSupport.awaitIdle;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncplayTransportTest {
    private final SyncplayCodec codec = new SyncplayCodec();

    @Test
    void reconnectRepeatsAuthenticationAndRestoresServerAuthorityAfterMalformedFrame() throws Exception {
        AtomicReference<PlayerState> live = new AtomicReference<>(new PlayerState());
        PlayerManager player = mock(PlayerManager.class);
        when(player.getStatus()).thenAnswer(call -> live.get().copy());
        doAnswer(call -> { live.set(new PlayerState(call.getArgument(0))); return null; })
                .when(player).updateStatus(any());
        try (ServerSocket server = new ServerSocket(0);
             SyncplayClient client = new SyncplayClient("127.0.0.1", server.getLocalPort(), player)) {
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try {
                    HelloMessage first;
                    try (Socket socket = accept(server)) {
                        first = handshake(socket);
                        assertEquals("alice", first.getUsername());
                        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", first.getPassword());
                        greetAndSeek(socket, 42);
                        assertEquals(42, nextState(socket).getPlaystate().getPosition());
                        // Exercise the parser failure path, not only orderly EOF.
                        socket.getOutputStream().write("{bad}\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                    try (Socket socket = accept(server)) {
                        HelloMessage second = handshake(socket);
                        assertEquals(first, second);
                        greetAndSeek(socket, 70);
                        StateMessage ack = nextState(socket);
                        assertEquals(70, ack.getPlaystate().getPosition());
                        assertFalse(ack.hasIgnoringOnTheFly());
                        // Leave the reader connected until the test explicitly closes it.
                        assertEquals(-1, socket.getInputStream().read());
                    }
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            client.login("alice", "room", "password");
            waitFor(() -> live.get().getPosition() == 70);
            client.close();
            awaitIdle(client);
            peer.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void explicitServerErrorStopsRetrying() throws Exception {
        PlayerManager player = mock(PlayerManager.class);
        try (ServerSocket server = new ServerSocket(0);
             SyncplayClient client = new SyncplayClient("127.0.0.1", server.getLocalPort(), player)) {
            client.login("alice", "room", null);
            try (Socket socket = accept(server)) {
                handshake(socket);
                write(socket, SyncplayMessage.newBuilder().setError(ErrorMessage.newBuilder().setMessage("Wrong password")).build());
                assertEquals(-1, socket.getInputStream().read());
            }
            server.setSoTimeout(500);
            assertThrows(java.net.SocketTimeoutException.class, server::accept);
        }
    }

    @Test
    void closeInterruptsReaderAndCancelsRetry() throws Exception {
        SyncplayTransport transport = new SyncplayTransport();
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> connected = new CompletableFuture<>();
            transport.callbacks(() -> connected.complete(null), () -> {}, () -> fail("Fatal connection"));
            transport.connect("127.0.0.1", server.getLocalPort(), codec);
            try (Socket socket = accept(server)) {
                assertEquals("send", codec.decode(socket.getInputStream()).getTls().getStartTls());
                write(socket, SyncplayMessage.newBuilder().setTls(TLSMessage.newBuilder().setStartTls("false")).build());
                connected.get(3, TimeUnit.SECONDS);
                transport.close();
                assertEquals(-1, socket.getInputStream().read());
            }
            server.setSoTimeout(300);
            assertThrows(java.net.SocketTimeoutException.class, server::accept);
        } finally { transport.close(); }
    }

    @Test
    void chatterAndPartialFramesDoNotExtendStateTimeout() throws Exception {
        SyncplayTransport transport = new SyncplayTransport((SSLSocketFactory) SSLSocketFactory.getDefault(), 300);
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> disconnected = new CompletableFuture<>();
            transport.callbacks(() -> {}, () -> disconnected.complete(null), () -> {});
            transport.connect("127.0.0.1", server.getLocalPort(), codec);
            try (Socket socket = accept(server)) {
                codec.decode(socket.getInputStream());
                write(socket, SyncplayMessage.newBuilder().setTls(TLSMessage.newBuilder().setStartTls("false")).build());
                for (int count = 0; count < 3; count++) {
                    socket.getOutputStream().write("{\"Chat\":\"hi\"}\r\n".getBytes(StandardCharsets.UTF_8));
                    Thread.sleep(50);
                }
                socket.getOutputStream().write("{\"State\":".getBytes(StandardCharsets.UTF_8));
                disconnected.get(2, TimeUnit.SECONDS);
            }
        } finally { transport.close(); }
    }

    @Test
    void legacyStartTlsRejectionReconnectsWithoutNegotiatingAgain() throws Exception {
        try (ServerSocket server = new ServerSocket(0); SyncplayTransport transport = new SyncplayTransport()) {
            transport.callbacks(() -> transport.send(SyncplayMessage.newBuilder()
                    .setHello(HelloMessage.newBuilder().setUsername("alice")).build()),
                    () -> {}, () -> fail("Legacy fallback failed"));
            transport.connect("localhost", server.getLocalPort(), codec);
            try (Socket first = accept(server)) {
                assertEquals("send", codec.decode(first.getInputStream()).getTls().getStartTls());
                write(first, SyncplayMessage.newBuilder()
                        .setError(ErrorMessage.newBuilder().setMessage("Unknown command startTLS")).build());
                assertEquals(-1, first.getInputStream().read());
            }
            try (Socket plain = accept(server)) {
                SyncplayMessage message = codec.decode(plain.getInputStream());
                assertTrue(message.hasHello());
                assertEquals("alice", message.getHello().getUsername());
                transport.close();
                assertEquals(-1, plain.getInputStream().read());
            }
        }
    }

    @Test
    void unexpectedTlsReplyRetriesNegotiationBeforeAuthentication() throws Exception {
        try (ServerSocket server = new ServerSocket(0); SyncplayTransport transport = new SyncplayTransport()) {
            CompletableFuture<Void> connected = new CompletableFuture<>();
            transport.callbacks(() -> connected.complete(null), () -> {}, () -> fail("Unexpected fatal failure"));
            transport.connect("localhost", server.getLocalPort(), codec);
            try (Socket first = accept(server)) {
                assertEquals("send", codec.decode(first.getInputStream()).getTls().getStartTls());
                write(first, SyncplayMessage.newBuilder().setTls(TLSMessage.newBuilder().setStartTls("invalid")).build());
                assertEquals(-1, first.getInputStream().read());
                assertFalse(connected.isDone());
            }
            try (Socket second = accept(server)) {
                assertEquals("send", codec.decode(second.getInputStream()).getTls().getStartTls());
                write(second, SyncplayMessage.newBuilder().setTls(TLSMessage.newBuilder().setStartTls("false")).build());
                connected.get(3, TimeUnit.SECONDS);
                transport.close();
                assertEquals(-1, second.getInputStream().read());
            }
        }
    }

    @TempDir static Path temporary;
    private static SSLContext trustedTls;

    @BeforeAll
    static void prepareTls() throws Exception {
        Path keystore = temporary.resolve("test.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "test-password", "-keypass", "test-password",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).start();
        assertTrue(keytool.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, keytool.exitValue(), new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keystore)) { store.load(input, "test-password".toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-password".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        trustedTls = SSLContext.getInstance("TLS");
        trustedTls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    }

    @Test
    void startTlsCompletesBeforeSendingHello() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            SyncplayTransport transport = new SyncplayTransport(trustedTls.getSocketFactory());
            CompletableFuture<Void> received = new CompletableFuture<>();
            transport.callbacks(() -> transport.send(SyncplayMessage.newBuilder().setHello(HelloMessage.newBuilder()
                    .setUsername("alice").setVersion("1.7.7").setRoom(RoomInfo.newBuilder().setName("room"))).build()),
                    () -> {}, () -> received.completeExceptionally(new AssertionError("TLS failed")));
            transport.connect("localhost", server.getLocalPort(), codec);
            try (Socket socket = accept(server)) {
                assertEquals("send", codec.decode(socket.getInputStream()).getTls().getStartTls());
                write(socket, SyncplayMessage.newBuilder().setTls(TLSMessage.newBuilder().setStartTls("true")).build());
                try (SSLSocket tls = serverTls(socket)) {
                    tls.startHandshake();
                    assertEquals("alice", codec.decode(tls.getInputStream()).getHello().getUsername());
                    received.complete(null);
                    received.get(3, TimeUnit.SECONDS);
                    transport.close();
                    assertEquals(-1, tls.getInputStream().read());
                }
            } finally { transport.close(); }
        }
    }

    @Test
    void untrustedCertificateIsFatalAndNeverFallsBackToPlaintext() throws Exception {
        rejectTls(new SyncplayTransport(), "localhost");
    }

    @Test
    void trustedCertificateWithWrongHostnameIsFatal() throws Exception {
        rejectTls(new SyncplayTransport(trustedTls.getSocketFactory()), "127.0.0.1");
    }

    private void rejectTls(SyncplayTransport transport, String hostname) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> fatal = new CompletableFuture<>();
            transport.callbacks(() -> fatal.completeExceptionally(new AssertionError("Invalid TLS accepted")),
                    () -> {}, () -> fatal.complete(null));
            transport.connect(hostname, server.getLocalPort(), codec);
            try (Socket socket = accept(server)) {
                assertEquals("send", codec.decode(socket.getInputStream()).getTls().getStartTls());
                write(socket, SyncplayMessage.newBuilder().setTls(TLSMessage.newBuilder().setStartTls("true")).build());
                try (SSLSocket tls = serverTls(socket)) {
                    assertThrows(java.io.IOException.class, tls::startHandshake);
                }
            }
            fatal.get(5, TimeUnit.SECONDS);
            server.setSoTimeout(300);
            assertThrows(java.net.SocketTimeoutException.class, server::accept);
        } finally { transport.close(); }
    }

    private SSLSocket serverTls(Socket socket) throws Exception {
        SSLSocket tls = (SSLSocket) trustedTls.getSocketFactory().createSocket(socket, "localhost", socket.getPort(), true);
        tls.setUseClientMode(false);
        tls.setSoTimeout(5000);
        return tls;
    }

    private Socket accept(ServerSocket server) throws Exception {
        server.setSoTimeout(5000);
        Socket socket = server.accept();
        socket.setSoTimeout(5000);
        return socket;
    }

    private HelloMessage handshake(Socket socket) throws Exception {
        assertEquals("send", codec.decode(socket.getInputStream()).getTls().getStartTls());
        write(socket, SyncplayMessage.newBuilder().setTls(TLSMessage.newBuilder().setStartTls("false")).build());
        SyncplayMessage hello = codec.decode(socket.getInputStream());
        assertTrue(hello.hasHello());
        return hello.getHello();
    }

    private void greetAndSeek(Socket socket, double position) throws Exception {
        byte[] hello = codec.encode(SyncplayMessage.newBuilder().setHello(HelloMessage.newBuilder()
                .setUsername("alice_").setRoom(RoomInfo.newBuilder().setName("room")).setVersion("1.7.7")).build());
        byte[] state = codec.encode(SyncplayMessage.newBuilder().setState(StateMessage.newBuilder()
                .setPlaystate(PlayState.newBuilder().setPosition(position).setPaused(true))).build());
        OutputStream output = socket.getOutputStream();
        // A fragmented greeting followed by a coalesced State exercises both framing cases.
        output.write(hello, 0, 7);
        output.flush();
        output.write(hello, 7, hello.length - 7);
        output.write(state);
        output.flush();
    }

    private StateMessage nextState(Socket socket) throws Exception {
        for (int count = 0; count < 5; count++) {
            SyncplayMessage message = codec.decode(socket.getInputStream());
            if (message.hasState()) return message.getState();
        }
        throw new AssertionError("No State received");
    }

    private void write(Socket socket, SyncplayMessage message) throws Exception {
        socket.getOutputStream().write(codec.encode(message));
        socket.getOutputStream().flush();
    }

    private static void waitFor(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(ready.getAsBoolean());
    }
}
