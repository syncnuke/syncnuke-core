package syncnuke.client.datasaver;

import lombok.extern.slf4j.Slf4j;
import syncnuke.client.SyncClient;
import syncnuke.client.datasaver.data.BaseData;
import syncnuke.client.datasaver.data.Command;
import syncnuke.client.datasaver.data.State;
import syncnuke.player.VideoPlayer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class DataSaverClient extends SyncClient {

    private final ThreadLocal<Boolean> serverCommandInProgress = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean ignoreUpdates = new AtomicBoolean(false);
    private final double debounceDelay;

    public DataSaverClient(String host, int port, double debounceDelay, VideoPlayer videoPlayer) {
        super(host, port, videoPlayer);
        this.debounceDelay = debounceDelay;
    }

    public DataSaverClient(String host, int port, VideoPlayer videoPlayer) {
        this(host, port, 0, videoPlayer);
    }

    @Override
    public void login(String username, String room) {
        // TODO: Implement room logic in datasaver stack
    }

    @Override
    protected void handleResponse(String resp) {
        try {
            BaseData data = BaseData.fromBytes(resp.getBytes(StandardCharsets.UTF_8));
            if (!ignoreUpdates.get()) {
                Command command = Objects.requireNonNull(data.getCommand());
                if (command == Command.UPDATE_STATE) {
                    handleStateUpdate(data);
                }
            }
        } catch (Exception e) {
            log.error("Error processing server response: {}", e.getMessage());
        }
    }

    private void handleStateUpdate(BaseData data) {
        State currentState = isPaused() ? State.PAUSED : State.PLAYING;

        if (data.getState() != currentState) {
            serverCommandInProgress.set(true);
            try {
                if (data.getState() == State.PLAYING) {
                    play();
                    log.info("Play command executed from server");
                } else {
                    pause();
                    log.info("Pause command executed from server");
                }
                if (isSignificantChange(data.getState().getCode(), data.getPosition())) {
                    seek(data.getPosition());
                    log.info("Synchronized seek with server during pause change: {}", data.getPosition());
                }
            } finally {
                serverCommandInProgress.set(false);
            }
            updateTracking(data.getState().getCode(), data.getPosition());
        }
    }

    @Override
    public void onPlay() {
        if (serverCommandInProgress.get()) return;
        log.debug("Play event detected");

        State currentState = State.PLAYING;
        double currentProgress = getPosition();
        if (isSignificantChange(currentState.getCode(), currentProgress)) {
            sendState(currentState, currentProgress);
        }

        updateTracking(currentState.getCode(), currentProgress);
    }

    @Override
    public void onPause() {
        if (serverCommandInProgress.get()) return;
        log.debug("Pause event detected");

        State currentState = State.PAUSED;
        double currentProgress = getPosition();
        if (isSignificantChange(currentState.getCode(), currentProgress)) {
            sendState(currentState, currentProgress);
        }

        updateTracking(currentState.getCode(), currentProgress);
    }

    @Override
    public void onSeek(double position) {
        if (serverCommandInProgress.get()) return;
        log.debug("Seek event detected: {}", position);

        State currentState = isPaused() ? State.PAUSED : State.PLAYING;
        if (isSignificantChange(currentState.getCode(), position)) {
            sendState(currentState, position);
        }

        updateTracking(currentState.getCode(), position);
    }

    private void sendState(State state, double progress) {
        try {
            byte[] message = new BaseData(
                    Command.UPDATE_STATE,
                    state,
                    progress
            ).toBytes();

            ignoreUpdates.set(true);
            log.info("Sending state: status={}, progress={}", state, progress);
            send(new String(message, StandardCharsets.UTF_8));
            Thread.sleep((long) (debounceDelay * 1000));
        } catch (InterruptedException e) {
            log.error("Failed to send state: {}", e.getMessage());
        } finally {
            ignoreUpdates.set(false);
        }
    }

}
