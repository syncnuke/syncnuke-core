package io.github.syncnuke.client.internal.datasaver;

import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.net.TcpClient;
import lombok.extern.slf4j.Slf4j;
import io.github.syncnuke.client.SyncClient;
import io.github.syncnuke.client.internal.datasaver.data.BaseCodec;
import io.github.syncnuke.client.internal.datasaver.data.BaseData;
import io.github.syncnuke.client.internal.datasaver.data.Command;
import io.github.syncnuke.client.internal.datasaver.data.State;
import io.github.syncnuke.player.VideoPlayer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class DataSaverClient extends SyncClient<BaseData> {

    private final ThreadLocal<Boolean> serverCommandInProgress = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    private final AtomicBoolean ignoreUpdates = new AtomicBoolean(false);
    private final int debounceDelay; // in milliseconds

    public DataSaverClient(String host, int port, int debounceDelay, VideoPlayer videoPlayer) {
        super(10000, videoPlayer);
        connect(host, port, new BaseCodec());
        this.debounceDelay = debounceDelay;
    }

    public DataSaverClient(String host, int port, VideoPlayer videoPlayer) {
        this(host, port, 0, videoPlayer);
    }

    @Override
    protected NetClient<BaseData> createNetClient() {
        return new TcpClient<>();
    }

    @Override
    public void login(String username, String room) {
        // TODO: Implement room logic in datasaver stack
    }

    @Override
    protected void handleResponse(BaseData data) {
        try {
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
        State currentState = getPlayer().isPaused() ? State.PAUSED : State.PLAYING;

            serverCommandInProgress.set(true);
            try {
                if (data.getState() != currentState) {
                    if (data.getState() == State.PLAYING) {
                        getPlayer().play();
                        log.info("Play command executed from server");
                    } else {
                        getPlayer().pause();
                        log.info("Pause command executed from server");
                    }
                }
                if (isSignificantChange(data.getState().getCode(), data.getPosition())) {
                    getPlayer().seek(data.getPosition());
                    log.info("Synchronized seek with server during pause change: {}", data.getPosition());
                }
                updateTracking(data.getState().getCode(), data.getPosition());
            } finally {
                serverCommandInProgress.set(false);
            }
    }

    @Override
    public void onPlay() {
        if (serverCommandInProgress.get()) return;
        log.debug("Play event detected");

        State currentState = State.PLAYING;
        double currentProgress = getPlayer().getPosition();
        sendState(currentState, currentProgress);
    }

    @Override
    public void onPause() {
        if (serverCommandInProgress.get()) return;
        log.debug("Pause event detected");

        State currentState = State.PAUSED;
        double currentProgress = getPlayer().getPosition();
        sendState(currentState, currentProgress);
    }

    @Override
    public void onSeek(double position) {
        if (serverCommandInProgress.get()) return;
        log.debug("Seek event detected: {}", position);

        State currentState = getPlayer().isPaused() ? State.PAUSED : State.PLAYING;
        sendState(currentState, position);
    }

    @Override
    protected void sendKeepAlive() {
        State currentState = getPlayer().isPaused() ? State.PAUSED : State.PLAYING;
        double currentProgress = getPlayer().getPosition();
        sendState(currentState, currentProgress);
    }

    private void sendState(State state, double progress) {
        boolean isSignificantChange = isSignificantChange(state.getCode(), progress);
        updateTracking(state.getCode(), progress);
        if (!isSignificantChange) {
            return;
        }

        try {
            BaseData message = new BaseData(
                    Command.UPDATE_STATE,
                    state,
                    progress
            );

            ignoreUpdates.set(true);
            log.info("Sending state: status={}, progress={}", state, progress);
            send(message);
            Thread.sleep(debounceDelay);
        } catch (InterruptedException e) {
            log.error("Failed to send state: {}", e.getMessage());
        } finally {
            ignoreUpdates.set(false);
        }
    }

}
