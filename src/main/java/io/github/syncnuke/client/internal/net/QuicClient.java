package io.github.syncnuke.client.internal.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A {@link NetClient} transported over a single bidirectional QUIC stream.
 *
 * <p>The QUIC stream is exposed to the existing {@link Codec} as one ordered
 * byte stream. This keeps transport framing out of protocol codecs.</p>
 */
@Slf4j
public class QuicClient<T> implements NetClient<T> {

    static final String APPLICATION_PROTOCOL = "syncnuke";
    static final String TRUSTED_CERTIFICATE_PROPERTY = "syncnuke.quic.trustedCertificate";

    private static final long MAX_IDLE_TIMEOUT_MILLIS = 30000;
    private static final long INITIAL_MAX_DATA = 10_000_000;
    private static final long INITIAL_MAX_STREAM_DATA = 1_000_000;
    private static final int MAX_PENDING_CHUNKS = 64;

    private final MultiThreadIoEventLoopGroup eventLoopGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final ExecutorService decoderExecutor = Executors.newSingleThreadExecutor();
    private final Set<NetListener<T>> listeners = new CopyOnWriteArraySet<>();

    private volatile boolean closed;
    private Codec<T> codec;
    private Channel datagramChannel;
    private QuicChannel quicChannel;
    private QuicStreamChannel streamChannel;
    private QueuedInputStream inbound;

    @Override
    public synchronized void connect(String host, int port, Codec<T> codec) {
        if (closed) {
            throw new IllegalStateException("QUIC client is closed");
        }
        if (isConnected()) {
            disconnect();
        }
        openConnection(host, port, codec);
    }

    private void openConnection(String host, int port, Codec<T> codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
        QueuedInputStream connectionInbound = new QueuedInputStream();
        this.inbound = connectionInbound;

        try {
            QuicSslContext sslContext = createSslContext();
            ChannelHandler clientCodec = new QuicClientCodecBuilder()
                    .sslEngineProvider(channel -> sslContext.newEngine(channel.alloc(), host, port))
                    .maxIdleTimeout(MAX_IDLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    .initialMaxData(INITIAL_MAX_DATA)
                    .initialMaxStreamDataBidirectionalLocal(INITIAL_MAX_STREAM_DATA)
                    .initialMaxStreamDataBidirectionalRemote(INITIAL_MAX_STREAM_DATA)
                    // The application uses the single stream opened below; the
                    // server responds on that stream rather than opening its own.
                    .initialMaxStreamsBidirectional(0)
                    .build();

            datagramChannel = new Bootstrap()
                    .group(eventLoopGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(clientCodec)
                    .bind(0)
                    .sync()
                    .channel();

            quicChannel = QuicChannel.newBootstrap(datagramChannel)
                    .handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel channel) {
                            channel.pipeline().addLast(new InboundHandler(connectionInbound));
                        }
                    })
                    .remoteAddress(new InetSocketAddress(host, port))
                    .connect()
                    .get();

            streamChannel = quicChannel.createStream(
                    QuicStreamType.BIDIRECTIONAL,
                    new InboundHandler(connectionInbound)
            ).get();
            startDecoding(connectionInbound, codec);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new RuntimeException("Interrupted while connecting to QUIC server", e);
        } catch (Exception e) {
            close();
            log.error("Failed to connect to QUIC server: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static QuicSslContext createSslContext() {
        QuicSslContextBuilder builder = QuicSslContextBuilder.forClient()
                .endpointIdentificationAlgorithm("HTTPS")
                .applicationProtocols(APPLICATION_PROTOCOL);
        String certificatePath = System.getProperty(TRUSTED_CERTIFICATE_PROPERTY);
        if (certificatePath != null && !certificatePath.trim().isEmpty()) {
            File certificate = new File(certificatePath);
            if (!certificate.isFile()) {
                throw new IllegalArgumentException("Trusted QUIC certificate does not exist: " + certificate);
            }
            builder.trustManager(certificate);
        }
        return builder.build();
    }

    private void startDecoding(QueuedInputStream connectionInbound, Codec<T> connectionCodec) {
        decoderExecutor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    T data = connectionCodec.decode(connectionInbound);
                    if (data != null) {
                        notifyListeners(data);
                    }
                }
            } catch (IOException e) {
                if (!closed && !connectionInbound.isFinished()) {
                    log.error("QUIC connection failed: {}", e.getMessage());
                    disconnect(connectionInbound);
                }
            } catch (RuntimeException e) {
                if (!closed && !connectionInbound.isFinished()) {
                    log.error("Failed to decode QUIC data: {}", e.getMessage(), e);
                    disconnect(connectionInbound);
                }
            }
        });
    }

    private void notifyListeners(T data) {
        for (NetListener<T> listener : listeners) {
            try {
                listener.onResponse(data);
            } catch (Exception e) {
                log.error("Error in listener: {}", e.getMessage(), e);
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
        if (!isConnected()) {
            throw new IllegalStateException("QUIC stream is not connected");
        }

        try {
            byte[] encoded = codec.encode(data);
            log.debug("Sending: {}", data);
            streamChannel.writeAndFlush(Unpooled.wrappedBuffer(encoded)).sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while sending QUIC data", e);
        } catch (IOException e) {
            log.error("Failed to send QUIC data: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private boolean isConnected() {
        return streamChannel != null && streamChannel.isActive();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        disconnect();
        decoderExecutor.shutdownNow();
        eventLoopGroup.shutdownGracefully().syncUninterruptibly();
    }

    private synchronized void disconnect() {
        if (inbound != null) {
            inbound.finish();
            inbound = null;
        }
        closeChannels();
    }

    private synchronized void disconnect(QueuedInputStream connectionInbound) {
        if (inbound == connectionInbound) {
            disconnect();
        }
    }

    private void closeChannels() {
        closeChannel(streamChannel);
        closeChannel(quicChannel);
        closeChannel(datagramChannel);
        streamChannel = null;
        quicChannel = null;
        datagramChannel = null;
    }

    private static void closeChannel(Channel channel) {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
    }

    private final class InboundHandler extends ChannelInboundHandlerAdapter {

        private final QueuedInputStream connectionInbound;

        private InboundHandler(QueuedInputStream connectionInbound) {
            this.connectionInbound = connectionInbound;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            ByteBuf bytes = (ByteBuf) message;
            try {
                byte[] data = new byte[bytes.readableBytes()];
                bytes.readBytes(data);
                if (!connectionInbound.offer(data)) {
                    connectionInbound.finish();
                    context.close();
                }
            } finally {
                bytes.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            connectionInbound.finish();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) {
            if (event == ChannelInputShutdownReadComplete.INSTANCE) {
                connectionInbound.finish();
            }
            context.fireUserEventTriggered(event);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            log.error("QUIC stream error: {}", cause.getMessage());
            connectionInbound.finish();
            context.close();
        }
    }

    /**
     * Adapts asynchronously received QUIC buffers to the blocking stream API
     * used by protocol codecs.
     */
    private static final class QueuedInputStream extends InputStream {

        private static final byte[] END_OF_STREAM = new byte[0];

        private final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(MAX_PENDING_CHUNKS);
        private byte[] current;
        private int offset;
        private volatile boolean finished;

        boolean offer(byte[] bytes) {
            return finished || bytes.length == 0 || chunks.offer(bytes);
        }

        void finish() {
            if (!finished) {
                finished = true;
                chunks.offer(END_OF_STREAM);
            }
        }

        boolean isFinished() {
            return finished;
        }

        @Override
        public int read() throws IOException {
            if (!ensureChunk()) {
                return -1;
            }
            return current[offset++] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int targetOffset, int length) throws IOException {
            if (targetOffset < 0 || length < 0 || length > bytes.length - targetOffset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return 0;
            }
            if (!ensureChunk()) {
                return -1;
            }

            int copied = Math.min(length, current.length - offset);
            System.arraycopy(current, offset, bytes, targetOffset, copied);
            offset += copied;
            return copied;
        }

        private boolean ensureChunk() throws IOException {
            while (current == null || offset == current.length) {
                if (finished && chunks.isEmpty()) {
                    return false;
                }
                try {
                    current = chunks.take();
                    offset = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading QUIC data", e);
                }
                if (current == END_OF_STREAM) {
                    return false;
                }
            }
            return true;
        }
    }
}
