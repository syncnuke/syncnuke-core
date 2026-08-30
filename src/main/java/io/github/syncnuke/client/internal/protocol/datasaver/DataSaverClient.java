package io.github.syncnuke.client.internal.protocol.datasaver;

import io.github.syncnuke.client.SyncClient;
import io.github.syncnuke.client.internal.protocol.datasaver.data.BaseCodec;
import io.github.syncnuke.client.internal.protocol.datasaver.data.StateData;
import io.github.syncnuke.client.internal.protocol.datasaver.data.BaseData;
import io.github.syncnuke.client.internal.protocol.datasaver.data.JoinData;
import io.github.syncnuke.client.internal.protocol.datasaver.data.Command;
import io.github.syncnuke.client.internal.protocol.datasaver.data.State;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.net.TcpClient;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import io.github.syncnuke.service.TimingServiceImpl;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
public class DataSaverClient extends SyncClient<BaseData> {

    private static final int DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS = 10000;

    private final NetClient<BaseData> netClient;
    private final int debounceDelay; // in milliseconds

    private volatile PlayerState serverState;
    private static final double DRIFT_THRESHOLD = 0.1; // error threshold for drift detection in %
    private static final double MIN_PROG_CHANGE = 0.5; // min progress change in seconds to trigger server notification

    public DataSaverClient(String host, int port, int debounceDelay, PlayerManager videoPlayer) {
        this(host, port, debounceDelay, DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS, videoPlayer);
    }

    public DataSaverClient(String host, int port, int debounceDelay, int keepAliveInterval, PlayerManager videoPlayer) {
        this(host, port, debounceDelay, keepAliveInterval, videoPlayer, new TcpClient<>(), new TimingServiceImpl());
    }

    public DataSaverClient(String host, int port, PlayerManager videoPlayer) {
        this(host, port, 0, videoPlayer);
    }

    DataSaverClient(String host, int port, int debounceDelay, int keepAliveInterval, PlayerManager videoPlayer,
                    NetClient<BaseData> netClient, TimingService timingService) {
        super(keepAliveInterval, videoPlayer, timingService);
        this.netClient = Objects.requireNonNull(netClient, "netClient");
        this.debounceDelay = debounceDelay;
        connect(host, port, new BaseCodec());
    }

    @Override
    protected NetClient<BaseData> getNetClient() {
        return netClient;
    }

    @Override
    public void login(String username, String room) {
        send(new JoinData(Command.JOIN_ROOM, Objects.requireNonNull(room, "room")));
    }

    @Override
    protected void handleResponse(BaseData data) {
        try {
            Command command = Objects.requireNonNull(data.getCommand());
            if (command == Command.UPDATE_STATE) {
                handleStateUpdate((StateData) data);
            }
        } catch (Exception e) {
            log.error("Error processing server response: {}", e.getMessage());
        }
    }

    private void handleStateUpdate(StateData data) {
        PlayerState serverStatus = getPlayer().getStatus();
        serverStatus.setPlaybackState(
                data.getState() == State.PAUSED
                        ? PlaybackState.PAUSED
                        : PlaybackState.PLAYING
        );
        serverStatus.setPosition(data.getPosition());
        serverStatus.setPlaybackSpeed(data.getPlaybackSpeed());

        updateServerState(serverStatus);
        getPlayer().updateStatus(serverStatus);
    }

    @Override
    public void onStatusChange(PlayerState status) {
        log.debug("Player status event detected: {}", status);
        if (isSignificantChange(status)) {
            sendState(status);
        }
    }

    @Override
    protected void sendKeepAlive() {
        sendState(getPlayer().getStatus());
    }

    private State getState(PlayerState status) {
        return status.getPlaybackState() == PlaybackState.PAUSED ? State.PAUSED : State.PLAYING;
    }

    private void sendState(PlayerState status) {
        try {
            State state = getState(status);
            StateData message = new StateData(
                    Command.UPDATE_STATE,
                    state,
                    status.getPosition(),
                    status.getPlaybackSpeed()
            );

            log.info("Sending state: status={}, progress={}", state, status.getPosition());
            send(message);
            Thread.sleep(debounceDelay);
        } catch (InterruptedException e) {
            log.error("Failed to send state: {}", e.getMessage());
        }
    }

    /**
     * Used to filter out video state updates that are too minor to trigger server notifications.
     *
     * @param localStatus the status reported by the local video player
     * @return  {@code true} if the change is significant enough to notify the server, {@code false} otherwise
     */
    private boolean isSignificantChange(PlayerState localStatus) {
        Objects.requireNonNull(localStatus, "localStatus");
        if (serverState == null) {
            return true;
        }

        if (localStatus.getPlaybackState() != serverState.getPlaybackState()) {
            // Status changed
            return true;
        }

        if (Math.abs(localStatus.getPosition() - serverState.getPosition()) <= MIN_PROG_CHANGE) {
            // Progress change is not significant enough
            return false;
        }

        long currentTime = getCurrentTime();
        long timeDiff = currentTime - serverState.getLastUpdateTime();

        boolean paused = localStatus.getPlaybackState() == PlaybackState.PAUSED;
        double playbackSpeed = localStatus.getPlaybackSpeed();
        if (paused || timeDiff <= 0 || playbackSpeed <= 0) {
            // Return exclusively based on progress change
            return true;
        }

        double expectedAdvance = timeDiff * playbackSpeed;
        if ((long) expectedAdvance == 0) {
            // Prevent division by zero
            return false;
        }
        double positionDiff = Math.abs(localStatus.getPosition() - serverState.getPosition()) * 1000; // in milliseconds
        double relativeError = Math.abs(positionDiff - expectedAdvance) / expectedAdvance;

        // Progress change does not match expected change based on playback speed
        return relativeError > DRIFT_THRESHOLD;
    }

    /**
     * Records playback state decoded from an inbound server command.
     */
    private void updateServerState(PlayerState serverStatus) {
        PlayerState expectation = new PlayerState(Objects.requireNonNull(serverStatus, "serverStatus"));
        expectation.setLastUpdateTime(getCurrentTime());
        serverState = expectation;
    }

}
