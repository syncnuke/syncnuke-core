package syncnuke.syncplay;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.data.*;
import syncnuke.syncplay.extractor.FileDataExtractor;
import syncnuke.syncplay.state.PlaybackState;
import syncnuke.tcp.DataProcessor;
import syncnuke.tcp.TcpClient;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SyncplayClient extends TcpClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    private final ScheduledExecutorService scheduler;
    private final DataProcessor dataProcessor;
    private FileDataExtractor fileDataExtractor;

    private boolean loggedIn = false;
    private final PlaybackState state;
    private String username;
    private String room;

    public SyncplayClient(DataProcessor dataProcessor) {
        super(SERVER_HOST, SERVER_PORT);
        this.dataProcessor = dataProcessor;
        scheduler = Executors.newScheduledThreadPool(1);
        state = new PlaybackState(null, 0, true, false);
    }

    private void logout() {
        if (loggedIn) {
            scheduler.shutdown();
        }
    }

    public void login(String username, String room) {
        logout();
        this.username = username;
        this.room = room;
        fileDataExtractor = new FileDataExtractor(username);
        send(new HelloData(username, room));
        loggedIn = true;
        startStateUpdates();
    }

    @Override
    protected void handleResponse(String line) {
        log.debug("Server response: {}", line);
        try {
            Optional<BaseData> response = dataProcessor.get(line);
            if (response.isEmpty()) {
                throw new RuntimeException("No command found in: " + line);
            }
            BaseData data = response.get();
            if (data instanceof StateData stateData) {
                handleStateUpdate(stateData);
            } else if (data instanceof SetData setData) {
                handleSetUpdate(setData);
            }
        } catch (Exception e) {
            log.error("Failed to parse server response: {}", e.getMessage());
        }
    }

    /**
     * Processes a 'State' command from the server.
     */
    private void handleStateUpdate(StateData stateData) {
        state.updateState(
                stateData.getPlaystate().getPosition(),
                stateData.getPlaystate().isPaused(),
                stateData.getPlaystate().isDoSeek()
        );

        if (stateData.getFile() != null) {
            state.updateFile(stateData.getFile());
            log.info("New file loaded: {}", stateData.getFile().getName());
        }

        log.debug("State updated - Position: {}, Paused: {}", state.getPosition(), state.isPaused());
    }

    /**
     * Processes a 'Set' command from the server.
     */
    private void handleSetUpdate(SetData setData) {
        log.debug("Server set data: {}", setData);
        FileData file = fileDataExtractor.extract(setData);
        if (file != null) {
            state.updateFile(file);
            acknowledgeFile();
            log.info("File set by server: {}", file.getName());
        }
    }

    private void acknowledgeFile() {
        if (state.hasFile()) {
            SetData setData = new SetData();
            setData.setFile(state.getCurrentFile());
            send(setData);
            log.info("Acknowledged file: {}", setData.getFile());
        }
    }

    private void keepAlive() {
        // TODO: Find a way to stay in sync with the server without being kicked (probably requires keeping track of timers)
        StateData stateData = new StateData(
                state.getPosition(),
                state.isPaused(),
                state.isDoSeek(),
                username,
                state.getCurrentFile()
        );
        send(stateData);
    }

    // Send state updates every 5 seconds
    private void startStateUpdates() {
        scheduler.scheduleAtFixedRate(this::keepAlive, 0, 5, TimeUnit.SECONDS);
    }

    public void send(BaseData data) {
        try {
            log.info("Sending data: {}", data.serialize());
            send(data.serialize());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize data: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

}
