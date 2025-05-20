package syncnuke.syncplay;

import lombok.extern.slf4j.Slf4j;
import syncnuke.client.SyncClient;
import syncnuke.syncplay.data.BaseData;
import syncnuke.syncplay.data.FileData;
import syncnuke.syncplay.data.commands.HelloData;
import syncnuke.syncplay.data.commands.SetData;
import syncnuke.syncplay.data.commands.StateData;
import syncnuke.syncplay.data.exception.SerializationException;
import syncnuke.syncplay.data.view.Views;
import syncnuke.syncplay.extractor.FileDataExtractor;
import syncnuke.player.VideoPlayer;
import syncnuke.tcp.DataProcessor;

import java.util.Optional;

@Slf4j
public class SyncplayClient extends SyncClient {
    private static final int DEFAULT_PORT = 8999;

    private final DataProcessor dataProcessor;
    private FileDataExtractor fileDataExtractor;

    // Current state of our client and player
    private final StateData state;
    private FileData file;

    private String username;

    public SyncplayClient(String host, int port, VideoPlayer videoPlayer) {
        this(new DataProcessor(), host, port, videoPlayer);
    }

    public SyncplayClient(DataProcessor dataProcessor, String host, VideoPlayer videoPlayer) {
        this(dataProcessor, host, DEFAULT_PORT, videoPlayer);
    }

    public SyncplayClient(DataProcessor dataProcessor, String host, int port, VideoPlayer videoPlayer) {
        super(host, port, videoPlayer);
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

    @Override
    public void onPlay() {
        log.debug("Play event detected");
        int currentStatus = 1;
        double currentProgress = getPosition();
        if (isSignificantChange(currentStatus, currentProgress)) {
            log.info("Play command sent due to significant change");
            state.getPlaystate().setPaused(false);
            acknowledgeState();
        }
        updateTracking(currentStatus, currentProgress);
    }

    @Override
    public void onPause() {
        log.debug("Pause event detected");
        int currentStatus = 0;
        double currentProgress = getPosition();
        if (isSignificantChange(currentStatus, currentProgress)) {
            log.info("Pause command sent due to significant change");
            state.getPlaystate().setPaused(true);
            acknowledgeState();
        }
        updateTracking(currentStatus, currentProgress);
    }

    @Override
    public void onSeek(double position) {
        if (state == null || state.getPlaystate() == null) {
            log.error("State or playstate is null during onSeek");
            return;
        }
        log.debug("Seek event detected: {}", position);
        int currentStatus = isPaused() ? 0 : 1;
        if (isSignificantChange(currentStatus, position)) {
            log.info("Seek command sent due to significant change");
            state.getPlaystate().setPosition(position);
            state.getPlaystate().setDoSeek(true);
            acknowledgeState();
        }
        updateTracking(currentStatus, position);
    }

    /**
     * Processes a 'State' command from the server.
     */
    private void handleStateUpdate(StateData stateData) {
        updatePing(stateData);

        if (wasSentByUs(stateData)) {
            // The server is telling us about our update to get on the same page
            updateIgnoringOnTheFly(stateData);
            // TODO: Test the effect of this in client timeouts, might work better without
            if (stateData.getPlaystate().isDoSeek()) {
                log.debug("Server acknowledged our seek request");
                state.getPlaystate().setDoSeek(false);
            }
            acknowledgeState();
            return;
        }

        updatePlayState(stateData);
        updateIgnoringOnTheFly(stateData);
        if (stateData.getPlaystate().isDoSeek()) {
            // We need to acknowledge twice to prove we've executed the seek
            acknowledgeState();
            state.getPlaystate().setDoSeek(false);
        }
        acknowledgeState();
    }

    private boolean wasSentByUs(StateData stateData) {
        if (stateData == null || stateData.getPlaystate() == null) {
            return false;
        }
        return username.equals(stateData.getPlaystate().getSetBy());
    }

    private void updatePlayState(StateData stateData) {
        boolean wasPaused = state.getPlaystate().isPaused();
        boolean isPaused = stateData.getPlaystate().isPaused();

        if (isPaused && !wasPaused) {
            pause();
        } else if (!isPaused && wasPaused) {
            play();
        }

        state.getPlaystate().setPaused(stateData.getPlaystate().isPaused());
        state.getPlaystate().setDoSeek(stateData.getPlaystate().isDoSeek());

        // Handle seeking
        if (stateData.getPlaystate().isDoSeek()) {
            log.debug("Seek detected, adjusting position to: {}", stateData.getPlaystate().getPosition());
            state.getPlaystate().setPosition(stateData.getPlaystate().getPosition());
            seek(state.getPlaystate().getPosition());
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
        double time = getNow();
        state.getPing().setClientLatencyCalculation(time);
        double sentTime = stateData.getPing().getClientLatencyCalculation();
        // Get difference between current time and the time we sent our previous request
        double diff = time - sentTime;
        if (diff < 0) {
            diff = 0;
        }
        state.getPing().setClientRtt(diff);
    }

    private void acknowledgeState() {
        send(state);
        log.debug("State acknowledged at position: {}", state.getPlaystate().getPosition());
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
            load(file.getName()); // TODO: Ensure this only runs when switching files
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
