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
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
public final class MpvPlayer implements VideoPlayer, AutoCloseable {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AFUNIXSocket socket;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mpv-ipc-listener");
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
        return r.isNumber() ? r.asDouble() : 0.0;
    }

    @Override
    public boolean isPaused() {
        JsonNode r = getProperty("pause");
        return r.isBoolean() && r.asBoolean();
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

    /**
     * Sends a command synchronously and returns the `"data"` field of the reply (or null on error/time-out).
     */
    private JsonNode sendCommandForResult(ArrayNode command) {
        String reqId = UUID.randomUUID().toString();
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
            writer.write('\n'); // MPV expects newline-delimited JSON
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to write to MPV socket: {}", e.toString());
        }
    }

    /* -------- asynchronous event pump -------------- */

    private final ConcurrentMap<String, CompletableFuture<JsonNode>> pendingReplies = new ConcurrentHashMap<>();

    private void startAsyncEventPump() {
        ioExecutor.submit(() -> {
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    log.debug("Received raw event from MPV: {}", line);
                    JsonNode node = MAPPER.readTree(line);

                    /* ---- 1) reply to a request_id ---- */
                    if (node.has("request_id")) {
                        String id = node.path("request_id").asText();
                        CompletableFuture<JsonNode> cf = pendingReplies.get(id);
                        if (cf != null) {
                            cf.complete(node.path("data"));
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
        // pause property changes
        observeProperty(1, "pause");
        // time-pos changes (once per ~1 s, can be tuned with mpv option --property-update)
        observeProperty(2, "time-pos");
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
                boolean paused = data.asBoolean(false);
                log.info("Pause property changed: {}", paused);
                if (eventListener != null) {
                    if (paused) eventListener.onPause(); else eventListener.onPlay();
                }
            } else if ("time-pos".equals(name) && data.isNumber()) {
                double pos = data.asDouble();
                log.info("Time position changed: {}", pos);
                if (eventListener != null) {
                    eventListener.onSeek(pos);
                }
            }
        } else if ("seek".equals(evt)) {
            log.info("Seek event detected");
        }
    }

    /* ---------------- resource cleanup ------------- */

    @Override
    public void close() {
        try {
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
