package syncnuke.syncplay.player;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class MpvPlayer implements VideoPlayer, AutoCloseable {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger();

    private final AFUNIXSocket socket;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mpv-ipc-listener");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mpv-event-dispatch");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mpv-keep-alive");
        t.setDaemon(true);
        return t;
    });

    private volatile VideoPlayerEventListener eventListener;

    public MpvPlayer(@NonNull String socketPath) throws IOException {
        File sockFile = new File(socketPath);
        if (!sockFile.exists()) {
            throw new FileNotFoundException("Socket '" + socketPath + "' does not exist; did you launch mpv " +
                    "with --input-ipc-server=" + socketPath + " ?");
        }

        this.socket = AFUNIXSocket.newInstance();
        this.socket.connect(AFUNIXSocketAddress.of(sockFile));
        this.socket.setSoTimeout((int) READ_TIMEOUT.toMillis());

        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        startAsyncEventPump();
        subscribeDefaultEvents();
        startKeepAlivePings();
        log.info("Connected to MPV IPC at {}", socketPath);
    }

    @Override
    public void setEventListener(VideoPlayerEventListener listener) {
        this.eventListener = listener;
    }

    @Override
    public void play() {
        setProperty("pause", false);
    }

    @Override
    public void pause() {
        setProperty("pause", true);
    }

    @Override
    public void seek(double position) {
        sendCommand(MAPPER.createArrayNode()
                .add("set_property")
                .add("time-pos")
                .add(position));
    }

    @Override
    public double getPosition() {
        JsonNode r = getProperty("time-pos");
        return r != null && r.isNumber() ? r.asDouble() : 0.0;
    }

    @Override
    public boolean isPaused() {
        JsonNode r = getProperty("pause");
        return r != null && r.isBoolean() && r.asBoolean();
    }

    @Override
    public void load(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            log.error("File does not exist: {}", filePath);
            return;
        }
        sendCommand(MAPPER.createArrayNode()
                .add("loadfile")
                .add(filePath));
    }

    /* ---------------- internal helpers ------------- */

    private void setProperty(String name, Object value) {
        sendCommand(MAPPER.createArrayNode()
                .add("set_property")
                .add(name)
                .addPOJO(value));
    }

    private JsonNode getProperty(String name) {
        return sendCommandForResult(MAPPER.createArrayNode()
                .add("get_property")
                .add(name));
    }

    private JsonNode sendCommandForResult(ArrayNode command) {
        int reqId = REQUEST_COUNTER.incrementAndGet();
        ObjectNode msg = MAPPER.createObjectNode()
                .put("request_id", reqId)
                .set("command", command);

        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        pendingReplies.put(reqId, answer);

        sendRaw(msg);

        try {
            return answer.get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            log.error("IPC request {} failed: {}", reqId, e.toString());
            return null;
        } finally {
            pendingReplies.remove(reqId);
        }
    }

    private void sendCommand(ArrayNode command) {
        ObjectNode msg = MAPPER.createObjectNode().set("command", command);
        sendRaw(msg);
    }

    private void sendRaw(JsonNode obj) {
        try {
            String json = MAPPER.writeValueAsString(obj);
            writer.write(json);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to write to MPV socket: {}", e.toString());
        }
    }

    /* -------- asynchronous event pump -------------- */

    private final ConcurrentMap<Integer, CompletableFuture<JsonNode>> pendingReplies = new ConcurrentHashMap<>();

    private void startAsyncEventPump() {
        ioExecutor.submit(() -> {
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    log.debug("Received raw event from MPV: {}", line);
                    JsonNode node = MAPPER.readTree(line);

                    if (node.has("request_id")) {
                        int id = node.path("request_id").asInt();
                        CompletableFuture<JsonNode> cf = pendingReplies.get(id);
                        if (cf != null) {
                            if (node.has("error") && !"success".equals(node.get("error").asText())) {
                                cf.completeExceptionally(new IOException("MPV error: " + node.get("error").asText()));
                            } else {
                                cf.complete(node.get("data"));
                            }
                            continue;
                        }
                    }

                    /* ---- 2) async events ------------ */
                    if (node.has("event")) {
                        log.debug("Processing MPV event: {}", node.get("event").asText());
                        handleEvent(node);
                    }
                }
            } catch (IOException e) {
                log.error("Event pump stopped due to IOException: {}", e.getMessage(), e);
            } catch (Exception e) {
                log.error("Unexpected error in event pump: {}", e.getMessage(), e);
            }
        });
    }

    private void subscribeDefaultEvents() {
        observeProperty(1, "pause");
        observeProperty(2, "time-pos");
        observeProperty(3, "seek");
        log.info("Subscribed to default MPV events: pause, time-pos, seek");
    }

    private void observeProperty(int id, String property) {
        sendCommand(MAPPER.createArrayNode()
                .add("observe_property")
                .add(id)
                .add(property));
    }

    private void handleEvent(JsonNode node) {
        String evt = node.get("event").asText();
        log.debug("Handling MPV event: {}", evt);
        if ("property-change".equals(evt)) {
            String name = node.path("name").asText();
            JsonNode data = node.get("data");

            if ("pause".equals(name)) {
                if (data != null && data.isBoolean()) {
                    boolean paused = data.asBoolean(false);
                    log.info("Pause property changed: {}", paused);
                    if (eventListener != null) {
                        listenerExecutor.submit(() -> {
                            if (paused) eventListener.onPause();
                            else eventListener.onPlay();
                        });
                    }
                } else {
                    log.warn("Pause property change event received with null or invalid data");
                }
            } else if ("time-pos".equals(name)) {
                if (data != null && data.isNumber()) {
                    double pos = data.asDouble();
                    log.info("Time position changed: {}", pos);
                    if (eventListener != null) {
                        listenerExecutor.submit(() -> eventListener.onSeek(pos));
                    }
                } else {
                    log.warn("Time position change event received with null or invalid data");
                }
            } else if ("seek".equals(name)) {
                log.info("Seek event detected");
                if (eventListener != null) {
                    listenerExecutor.submit(() -> {
                        double position = getPosition();
                        eventListener.onSeek(position);
                    });
                }
            }
        }
    }

    private void startKeepAlivePings() {
        keepAliveExecutor.scheduleAtFixedRate(() -> {
            try {
                getProperty("pause");
            } catch (Exception e) {
                log.warn("Keep-alive ping failed: {}", e.getMessage());
            }
        }, 1, 3, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        try {
            keepAliveExecutor.shutdownNow();
            listenerExecutor.shutdownNow();
            socket.shutdownOutput();
            socket.shutdownInput();
            writer.close();
            reader.close();
            socket.close();
        } catch (IOException e) {
            log.error("Failed to close MPV socket: {}", e.toString());
        }
        ioExecutor.shutdownNow();
    }

}
