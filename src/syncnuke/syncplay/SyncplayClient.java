package syncnuke.syncplay;

import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.data.BaseData;
import syncnuke.syncplay.data.FileData;
import syncnuke.syncplay.data.commands.HelloData;
import syncnuke.syncplay.data.commands.SetData;
import syncnuke.syncplay.data.commands.StateData;
import syncnuke.syncplay.data.exception.SerializationException;
import syncnuke.syncplay.data.view.Views;
import syncnuke.syncplay.extractor.FileDataExtractor;
import syncnuke.tcp.DataProcessor;
import syncnuke.tcp.KeepAliveClient;

import java.util.Optional;

@Slf4j
public class SyncplayClient extends KeepAliveClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    private final DataProcessor dataProcessor;
    private FileDataExtractor fileDataExtractor;

    // Current state of our client and player
    private final StateData state;
    private FileData file;

    private boolean loggedIn = false;
    private String username;

    public SyncplayClient(DataProcessor dataProcessor) {
        super(SERVER_HOST, SERVER_PORT);
        this.dataProcessor = dataProcessor;
        state = new StateData(0, true, false, null);
        state.setIgnoringOnTheFly(new StateData.IgnoringOnTheFly());
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
        state.getPlaystate().setSetBy(username);
        startKeepAlive(5);
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
        // Ignore if the client that send ths state command was us
        if (wasSentByUs(stateData)) {
            return;
        }

        // TODO: Extract to update Playstate
        state.getPlaystate().setPosition(stateData.getPlaystate().getPosition());
        state.getPlaystate().setPaused(stateData.getPlaystate().isPaused());
        state.getPlaystate().setDoSeek(stateData.getPlaystate().isDoSeek());

        // Handle seeking
        if (stateData.getPlaystate().isDoSeek()) {
            log.info("Seek detected, adjusting position to: {}", stateData.getPlaystate().getPosition());
            state.getPlaystate().setPosition(stateData.getPlaystate().getPosition());
            stateData.getIgnoringOnTheFly().setClient(stateData.getIgnoringOnTheFly().getClient() + 1);
            acknowledgeSeek();
        }

        // TODO: Extract to update Ping
        // Handle latency and RTT
        double sentTime = stateData.getPing().getClientLatencyCalculation();
        setLastKnownRtt(sentTime);

        // TODO: Extract to updateIgnoringOnTheFly
        if (stateData.getIgnoringOnTheFly() != null) {
            int serverVal = stateData.getIgnoringOnTheFly().getServer();
            int clientVal = stateData.getIgnoringOnTheFly().getClient();

            if (serverVal == state.getIgnoringOnTheFly().getServer()) {
                state.getIgnoringOnTheFly().setServer(0);
            } else {
                state.getIgnoringOnTheFly().setServer(serverVal);
            }
            if (clientVal == state.getIgnoringOnTheFly().getClient()) {
                state.getIgnoringOnTheFly().setClient(0);
            } else {
                state.getIgnoringOnTheFly().setClient(clientVal);
            }
        } else {
            state.getIgnoringOnTheFly().setServer(0);
            state.getIgnoringOnTheFly().setClient(0);
        }

        acknowledgeState();
    }

    private void setLastKnownRtt(double latencyCalculation) {
        if (latencyCalculation  < 0) {
            return;
        }
        state.getPing().setClientRtt(getNow() - latencyCalculation);
        log.debug("New RTT: {}", state.getPing().getClientRtt());
    }

    private boolean wasSentByUs(StateData stateData) {
        if (stateData == null || stateData.getPlaystate() == null) {
            return false;
        }
        return username.equals(stateData.getPlaystate().getSetBy());
    }

    private void acknowledgeState() {
        // TODO: Send our own state directly instead of a new one
        StateData stateData = new StateData(
                state.getPlaystate().getPosition(),
                state.getPlaystate().isPaused(),
                state.getPlaystate().isDoSeek(),
                username
        );

        stateData.getPing().setClientLatencyCalculation(getNow());
        stateData.getPing().setClientRtt(state.getPing().getClientRtt());

        if (state.getIgnoringOnTheFly().getClient() > 0 || state.getIgnoringOnTheFly().getServer() > 0) {
            stateData.setIgnoringOnTheFly(
                    state.getIgnoringOnTheFly()
            );
        } else {
            stateData.setIgnoringOnTheFly(null);
        }

        send(stateData);
//        setLastKnownRtt(getNow());
        log.debug("State acknowledged at position: {}", state.getPlaystate().getPosition());
    }

    private void acknowledgeSeek() {
        // TODO: Use our own state instead of a new one
        StateData stateData = new StateData(
                state.getPlaystate().getPosition(),
                state.getPlaystate().isPaused(),
                true,
                username
        );

        if (state.getIgnoringOnTheFly().getClient() > 0 || state.getIgnoringOnTheFly().getServer() > 0) {
            stateData.setIgnoringOnTheFly(
                    state.getIgnoringOnTheFly()
            );
        }

        send(stateData);
        state.getPlaystate().setDoSeek(false);
        send(stateData);
        log.debug("Seek acknowledged at position: {}", state.getPlaystate().getPosition());
    }

    /**
     * Processes a 'Set' command from the server.
     */
    private void handleSetUpdate(SetData setData) {
        log.debug("Server set data: {}", setData);
        FileData file = fileDataExtractor.extract(setData);
        if (file != null) {
            this.file = file;
            acknowledgeFile();
            log.info("File set by server: {}", file.getName());
        }
    }

    private void acknowledgeFile() {
        if (file != null) {
            SetData setData = new SetData();
            setData.setFile(file);
            send(setData);
            log.info("Acknowledged file: {}", setData.getFile());
        }
    }

    @Override
    protected void keepAlive() {
        // TODO: Extract to isPlaying
        if (!state.getPlaystate().isPaused() || state.getPlaystate().isDoSeek()) {
            // During playback the server sends updates every second which we acknowledge, no need to keep alive
            return;
        }

        StateData stateData = new StateData(
                state.getPlaystate().getPosition(),
                true,
                false,
                username
        );
        stateData.setPing(null);

        send(stateData);
    }

    public void send(BaseData data) {
        try {
            log.info("Sending data: {}", data.serialize(Views.Client.class));
            send(data.serialize(Views.Client.class));
        } catch (SerializationException e) {
            log.error(e.getMessage(), e.getCause());
            throw new RuntimeException(e);
        }
    }

    /**
     * @return The current time, in seconds.
     */
    private double getNow() {
        return System.currentTimeMillis() / 1000.0;
    }

}
