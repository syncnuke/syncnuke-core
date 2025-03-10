package syncnuke.syncplay;

import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.data.BaseData;
import syncnuke.syncplay.data.FileData;
import syncnuke.syncplay.data.commands.HelloData;
import syncnuke.syncplay.data.commands.SetData;
import syncnuke.syncplay.data.commands.StateData;
import syncnuke.syncplay.data.exception.SerializationException;
import syncnuke.syncplay.extractor.FileDataExtractor;
import syncnuke.syncplay.state.PlaybackState;
import syncnuke.tcp.DataProcessor;
import syncnuke.tcp.KeepAliveClient;

import java.util.Optional;

@Slf4j
public class SyncplayClient extends KeepAliveClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    private final DataProcessor dataProcessor;
    private FileDataExtractor fileDataExtractor;

    private final PlaybackState state;

    private boolean loggedIn = false;
    private String username;

    public SyncplayClient(DataProcessor dataProcessor) {
        super(SERVER_HOST, SERVER_PORT);
        this.dataProcessor = dataProcessor;
        state = new PlaybackState(null, 0, true, false);
    }

    private void logout() {
        if (loggedIn) {
            stopKeepAlive();
        }
    }

    public void login(String username, String room) {
        logout();
        this.username = username;
        fileDataExtractor = new FileDataExtractor(username);
        send(new HelloData(username, room));
        loggedIn = true;
        // Send state updates every 5 seconds
        startKeepAlive(1);
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

        // Handle seeking
        if (stateData.getPlaystate().isDoSeek()) {
            log.info("Seek detected, adjusting position to: {}", stateData.getPlaystate().getPosition());
            state.setPosition(stateData.getPlaystate().getPosition());
            acknowledgeSeek();
        }

        log.debug("State updated - Position: {}, Paused: {}", state.getPosition(), state.isPaused());
    }

    private void acknowledgeSeek() {
        StateData stateData = new StateData(
                state.getPosition(),
                state.isPaused(),
                false,
                username,
                state.getCurrentFile()
        );
        send(stateData);
        state.clearSeek();
        log.debug("Seek acknowledged at position: {}", state.getPosition());
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

    @Override
    protected void keepAlive() {
        // TODO: Find a way to stay in sync with the server without being kicked (probably requires keeping track of timers)
        if (state.isDoSeek()) {
            return;
        }
        StateData stateData = new StateData(
                state.getPosition(),
                state.isPaused(),
                false,
                username,
                state.getCurrentFile()
        );
        send(stateData);
    }

    public void send(BaseData data) {
        try {
            log.info("Sending data: {}", data.serialize());
            send(data.serialize());
        } catch (SerializationException e) {
            log.error(e.getMessage(), e.getCause());
            throw new RuntimeException(e);
        }
    }

}
