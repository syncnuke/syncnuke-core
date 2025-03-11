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
import syncnuke.tcp.TcpClient;

import java.util.Optional;

@Slf4j
public class SyncplayClient extends TcpClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    private final DataProcessor dataProcessor;
    private FileDataExtractor fileDataExtractor;

    // Current state of our client and player
    private final StateData state;
    private FileData file;

    private String username;

    public SyncplayClient(DataProcessor dataProcessor) {
        super(SERVER_HOST, SERVER_PORT);
        this.dataProcessor = dataProcessor;
        state = new StateData(0, true, false, null);
    }

    public void login(String username, String room) {
        this.username = username;
        fileDataExtractor = new FileDataExtractor(username);
        // Announce ourselves to the server and join the room
        send(new HelloData(username, room));
        state.getPlaystate().setSetBy(username);
    }

    @Override
    protected void handleResponse(String line) {
        log.info("Server response: {}", line);
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
            log.error("Failed to parse server response", e);
        }
    }

    /**
     * Processes a 'State' command from the server.
     */
    private void handleStateUpdate(StateData stateData) {
        if (wasSentByUs(stateData)) {
            // The server is telling us about our update to get on the same page
            updateIgnoringOnTheFly(stateData);
            acknowledgeState();
            return;
        }

        updatePlayState(stateData);
        updateIgnoringOnTheFly(stateData);
        if (stateData.getPlaystate().isDoSeek()) {
            acknowledgeSeek();
        }
        updatePing(stateData);
        acknowledgeState();
    }

    private boolean wasSentByUs(StateData stateData) {
        if (stateData == null || stateData.getPlaystate() == null) {
            return false;
        }
        return username.equals(stateData.getPlaystate().getSetBy());
    }

    private void updatePlayState(StateData stateData) {
        state.getPlaystate().setPaused(stateData.getPlaystate().isPaused());
        state.getPlaystate().setDoSeek(stateData.getPlaystate().isDoSeek());

        // Handle seeking
        if (stateData.getPlaystate().isDoSeek()) {
            log.debug("Seek detected, adjusting position to: {}", stateData.getPlaystate().getPosition());
            state.getPlaystate().setPosition(stateData.getPlaystate().getPosition());
        }
    }

    private void updateIgnoringOnTheFly(StateData stateData) {
        if (state.getIgnoringOnTheFly() == null) {
            state.setIgnoringOnTheFly(new StateData.IgnoringOnTheFly(0, 0));
        }
        if (stateData.getIgnoringOnTheFly() != null) {
            int dataServerCount = stateData.getIgnoringOnTheFly().getServer();
            int dataClientCount = stateData.getIgnoringOnTheFly().getClient();

            if (dataServerCount > dataClientCount) {
                // Server told us to ignore more often, say ok by equalling the ignore counts
                state.getIgnoringOnTheFly().setClient(dataServerCount);
                state.getIgnoringOnTheFly().setServer(dataServerCount);
            } else if (dataServerCount + dataClientCount == 0) {
                // Server is acknowledging we both know we're back to listening to updates, remove ignore object from responses
                state.setIgnoringOnTheFly(null);
            } else {
                // Server is telling us it knows we both want to listen to updates, give it the go ahead
                state.getIgnoringOnTheFly().setServer(0);
                state.getIgnoringOnTheFly().setClient(0);
            }
        } else {
            state.setIgnoringOnTheFly(null);
        }
    }

    private void updatePing(StateData stateData) {
        double sentTime = stateData.getPing().getClientLatencyCalculation();
        setLastKnownRtt(sentTime);
    }

    private void setLastKnownRtt(double latencyCalculation) {
        if (latencyCalculation  < 0) {
            return;
        }
        state.getPing().setClientRtt(getNow() - latencyCalculation);
        log.debug("New RTT: {}", state.getPing().getClientRtt());
    }

    private void acknowledgeState() {
        state.getPing().setClientLatencyCalculation(getNow());
        send(state);
        log.debug("State acknowledged at position: {}", state.getPlaystate().getPosition());
    }

    private void acknowledgeSeek() {
        send(state);
        state.getPlaystate().setDoSeek(false);
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
