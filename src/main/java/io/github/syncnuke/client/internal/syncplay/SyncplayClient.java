package io.github.syncnuke.client.internal.syncplay;

import com.google.protobuf.Message;
import io.github.syncnuke.client.SyncClient;
import io.github.syncnuke.client.internal.syncplay.data.CommandMessage;
import io.github.syncnuke.client.internal.syncplay.data.ProtoJsonCodec;
import io.github.syncnuke.client.internal.syncplay.data.builder.HelloMessageBuilder;
import io.github.syncnuke.client.internal.syncplay.data.exception.SerializationException;
import io.github.syncnuke.client.internal.syncplay.service.DataProcessor;
import io.github.syncnuke.client.internal.syncplay.service.extractor.FileDataExtractor;
import io.github.syncnuke.player.VideoPlayer;
import lombok.extern.slf4j.Slf4j;
import pl.syncplay.proto.SyncplayProto.*;

import java.util.Optional;

@Slf4j
public class SyncplayClient extends SyncClient<SyncplayMessage> {
    private static final int DEFAULT_PORT = 8999;
    private static final int PLAY_STATUS = 1, PAUSE_STATUS = 0;

    private final DataProcessor dataProcessor;
    private FileDataExtractor fileDataExtractor;

    // Current state of our client and player
    private final StateMessage.Builder state;
    private FileInfo file;

    private String username;

    public SyncplayClient(String host, int port, VideoPlayer videoPlayer) {
        this(new DataProcessor(), host, port, videoPlayer);
    }

    public SyncplayClient(DataProcessor dataProcessor, String host, VideoPlayer videoPlayer) {
        this(dataProcessor, host, DEFAULT_PORT, videoPlayer);
    }

    public SyncplayClient(DataProcessor dataProcessor, String host, int port, VideoPlayer videoPlayer) {
        super(host, port, new ProtoJsonCodec(), 1000, videoPlayer);
        this.dataProcessor = dataProcessor;
        state = StateMessage.newBuilder().setPlaystate(PlayState.newBuilder()
                .setPaused(true)
                .setDoSeek(false))
        ;
    }

    @Override
    public void login(String username, String room) {
        this.username = username;
        fileDataExtractor = new FileDataExtractor(username);
        // Announce ourselves to the server and join the room
        send(HelloMessageBuilder.create(username, room).build());
        state.getPlaystateBuilder().setSetBy(username);
    }

    @Override
    protected void handleResponse(SyncplayMessage msg) {
        log.info("Server response: {}", ProtoJsonCodec.raw(msg));
        try {

            Optional<CommandMessage> response = dataProcessor.get(msg);
            if (!response.isPresent()) {
                throw new RuntimeException("No command found in: " + msg);
            }
            CommandMessage data = response.get();
            switch (data.getCommand()) {        // classic Java-8 switch
                case STATE:
                    handleStateUpdate((StateMessage) data.getMessage());
                    break;
                case SET:
                    handleSetUpdate((SetCommand) data.getMessage());
                    break;
                default:
                    log.debug("Ignoring {}", data.getCommand());
            }
        } catch (Exception e) {
            log.error("Failed to parse server response", e);
        }
    }

    @Override
    public void onPlay() {
        log.debug("Play event detected");
        int currentStatus = PLAY_STATUS;
        double position = getPosition();
        if (isSignificantChange(currentStatus, position)) {
            log.info("Play command sent due to significant change");
            state.getPlaystateBuilder().setPosition(position);
            state.getPlaystateBuilder().setPaused(false);
            acknowledgeState();
        }
        updateTracking(currentStatus, position);
    }

    @Override
    public void onPause() {
        log.debug("Pause event detected");
        int currentStatus = PAUSE_STATUS;
        double position = getPosition();
        if (isSignificantChange(currentStatus, position)) {
            log.info("Pause command sent due to significant change");
            state.getPlaystateBuilder().setPosition(position);
            state.getPlaystateBuilder().setPaused(true);
            acknowledgeState();
        }
        updateTracking(currentStatus, position);
    }

    @Override
    public void onSeek(double position) {
        if (state == null || state.getPlaystate() == null) {
            log.error("State or playstate is null during onSeek");
            return;
        }
        log.debug("Seek event detected: {}", position);
        int currentStatus = isPaused() ? PAUSE_STATUS : PLAY_STATUS;
        if (isSignificantChange(currentStatus, position)) {
            log.info("Seek command sent due to significant change");
            state.getPlaystateBuilder().setPosition(position);
            state.getPlaystateBuilder().setDoSeek(true);
            acknowledgeState();
        }
        updateTracking(currentStatus, position);
    }

    @Override
    protected void sendKeepAlive() {
        boolean isPaused = isPaused();
        double position = getPosition();
        state.getPlaystateBuilder().setPaused(isPaused);
        state.getPlaystateBuilder().setPosition(position);
        send(state.build());
        updateTracking(isPaused ? PAUSE_STATUS : PLAY_STATUS, position);
    }

    /**
     * Processes a 'State' command from the server.
     */
    private void handleStateUpdate(StateMessage stateData) {
        updatePing(stateData);

        if (wasSentByUs(stateData)) {
            // The server is telling us about our update to get on the same page
            updateIgnoringOnTheFly(stateData);
            // TODO: Test the effect of this in client timeouts, might work better without
            if (stateData.getPlaystate().getDoSeek()) {
                log.debug("Server acknowledged our seek request");
                state.getPlaystateBuilder().setDoSeek(false);
            }
            acknowledgeState();
            return;
        }

        updatePlayState(stateData);
        updateIgnoringOnTheFly(stateData);
        if (stateData.getPlaystate().getDoSeek()) {
            // We need to acknowledge twice to prove we've executed the seek
            acknowledgeState();
            state.getPlaystateBuilder().setDoSeek(false);
        }
        acknowledgeState();
    }

    private boolean wasSentByUs(StateMessage stateData) {
        if (stateData == null || !stateData.hasPlaystate()) {
            return false;
        }
        return username.equals(stateData.getPlaystate().getSetBy());
    }

    private void updatePlayState(StateMessage stateData) {
        boolean wasPaused = isPaused();
        boolean isPaused = stateData.getPlaystate().getPaused();

        if (isPaused && !wasPaused) {
            pause();
        } else if (!isPaused && wasPaused) {
            play();
        }

        state.getPlaystateBuilder().setPaused(stateData.getPlaystate().getPaused());
        state.getPlaystateBuilder().setDoSeek(stateData.getPlaystate().getDoSeek());

        // Handle seeking
        if (stateData.getPlaystate().getDoSeek()) {
            log.debug("Seek detected, adjusting position to: {}", stateData.getPlaystate().getPosition());
            state.getPlaystateBuilder().setPosition(stateData.getPlaystate().getPosition());
            seek(state.getPlaystate().getPosition());
        }
    }

    private void updateIgnoringOnTheFly(StateMessage stateData) {
        if (!state.hasIgnoringOnTheFly()) {
            state.setIgnoringOnTheFly(state.getIgnoringOnTheFlyBuilder().clear());
        }
        if (stateData.hasIgnoringOnTheFly()) {
            int dataServerCount = stateData.getIgnoringOnTheFly().getServer();
            int dataClientCount = stateData.getIgnoringOnTheFly().getClient();

            if (dataServerCount > dataClientCount) {
                // Server told us to ignore more often, say ok by equalling the ignore counts
                state.setIgnoringOnTheFly(state.getIgnoringOnTheFlyBuilder()
                        .setClient(dataServerCount)
                        .setServer(dataServerCount)
                        .build()
                );
            } else if (dataServerCount + dataClientCount == 0) {
                // Server is acknowledging we both know we're back to listening to updates, remove ignore object from responses
                state.clearIgnoringOnTheFly();
            } else {
                // Server is telling us it knows we both want to listen to updates, give it the go ahead
                state.setIgnoringOnTheFly(state.getIgnoringOnTheFlyBuilder().clear().build()); // 0, 0
            }
        } else {
            state.clearIgnoringOnTheFly();
        }
    }

    private void updatePing(StateMessage stateData) {
        double time = getNow();
        state.getPingBuilder().setClientLatencyCalculation(time);
        double sentTime = stateData.getPing().getClientLatencyCalculation();
        // Get difference between current time and the time we sent our previous request
        double diff = Math.max(0, time - sentTime);
        state.getPingBuilder().setClientRtt(diff);
    }

    private void acknowledgeState() {
        // TODO: Remove parsing from VideoPlayer if facing bugs
        state.getPlaystateBuilder().setPaused(isPaused());
        state.getPlaystateBuilder().setPosition(getPosition());
        send(state.build());
        updateLastMessageSentTime();
        log.debug("State acknowledged at position: {}", state.getPlaystate().getPosition());
    }

    /**
     * Processes a 'Set' command from the server.
     */
    private void handleSetUpdate(SetCommand setData) {
        log.debug("Server set data: {}", setData);
        FileInfo file = fileDataExtractor.extract(setData);
        if (file != null) {
            this.file = file;
            acknowledgeFile();
            log.info("File set by server: {}", file.getName());
//            load(file.getName()); // TODO: Ensure this only runs when switching files
        } else {
            // {"Set": {"ready": {"username": "testme", "isReady": true, "manuallyInitiated": false}}}
            // TODO: Remove this and find a better way to handle playback start
            if (setData.hasReady()) {
                play();
                state.getPlaystateBuilder().setPaused(isPaused());
            }
        }
    }

    private void acknowledgeFile() {
        if (file != null) {
            SetCommand.Builder setData = SetCommand.newBuilder();
            setData.setFile(file);
            ReadySetting.Builder readyData = ReadySetting.newBuilder();
            readyData.setUsername(username);
            readyData.setIsReady(true);
            setData.setReady(readyData.build());
            send(setData.build());
            log.info("Acknowledged file: {}", setData.getFile());
        }
    }

    public void send(Message data) {
        try {
            SyncplayMessage.Builder env = SyncplayMessage.newBuilder();
            if (data instanceof HelloMessage) {
                env.setHello((HelloMessage) data);
            } else if (data instanceof StateMessage) {
                env.setState((StateMessage) data);
            } else if (data instanceof SetCommand) {
                env.setSet((SetCommand) data);
            } else {
                throw new SerializationException("Unsupported message: " + data.getClass());
            }
            SyncplayMessage msg = env.build();
            log.info("Sending data: {}", ProtoJsonCodec.raw(msg));
            super.send(msg);
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
