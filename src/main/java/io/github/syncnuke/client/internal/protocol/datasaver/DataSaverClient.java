package io.github.syncnuke.client.internal.protocol.datasaver;

import io.github.syncnuke.client.RoomInfo;
import io.github.syncnuke.client.SyncClient;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.net.TcpClient;
import io.github.syncnuke.client.internal.protocol.datasaver.data.*;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.service.TimingService;
import io.github.syncnuke.service.TimingServiceImpl;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Slf4j
public class DataSaverClient extends SyncClient<BaseData> {

    private static final int DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS = 10000;

    private final NetClient<BaseData> netClient;
    private volatile String username;
    private volatile String room;
    private volatile String password;
    private volatile List<String> users = Collections.emptyList();

    /**
     * Represents the playback state, as we believe would be seen from the server's perspective.
     * Needed for filtering updates before sending to the server - but should not affect processing of received messages.
     * This is separate from the {@link io.github.syncnuke.player.VideoPlayer}'s state, which represents
     * the local playback state.
     */
    private volatile PlayerState expectedState;
    private static final double DRIFT_THRESHOLD = 0.1; // error threshold for drift detection in %
    private static final double MIN_PROG_CHANGE = 0.5; // min progress change in seconds to trigger server notification

    public DataSaverClient(String host, int port, PlayerManager videoPlayer) {
        this(host, port, DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS, videoPlayer);
    }

    public DataSaverClient(String host, int port, int keepAliveInterval, PlayerManager videoPlayer) {
        this(host, port, keepAliveInterval, videoPlayer, new TcpClient<>(), new TimingServiceImpl());
    }

    DataSaverClient(String host, int port, int keepAliveInterval, PlayerManager videoPlayer,
                    NetClient<BaseData> netClient, TimingService timingService) {
        super(keepAliveInterval, videoPlayer, timingService);
        this.netClient = Objects.requireNonNull(netClient, "netClient");
        connect(host, port, new BaseCodec());
    }

    @Override
    protected NetClient<BaseData> getNetClient() {
        return netClient;
    }

    @Override
    public synchronized void login(String username, String room, String password) {
        this.username = Objects.requireNonNull(username, "username");
        this.room = Objects.requireNonNull(room, "room");
        this.password = password;
        users = Collections.singletonList(username);
        joinRoom();
    }

    @Override
    public synchronized RoomInfo getRoomInfo() {
        return new RoomInfo(room, new ArrayList<>(users));
    }

    @Override
    protected void handleResponse(BaseData data) {
        try {
            Command command = Objects.requireNonNull(data.getCommand());
            switch (command) {
                case UPDATE_STATE -> handleStateUpdate((StateData) data);
                case CONNECT -> handleConnect((ConnectData) data);
                case JOIN_ROOM -> handleJoin((JoinData) data);
                case LEAVE_ROOM -> handleLeave((LeaveData) data);
            }
        } catch (Exception e) {
            log.error("Error processing server response: {}", e.getMessage());
        }
    }

    private void handleConnect(ConnectData data) {
        log.info("Redirecting to {}:{}", data.getHost(), data.getPort());
        if (username != null) {
            users = Collections.singletonList(username);
        }
        netClient.connect(data.getHost(), data.getPort(), new BaseCodec());
        joinRoom();
    }

    private synchronized void handleJoin(JoinData data) {
        log.debug("Command: {} User: {} Room: {}", data.getCommand(), data.getUsername(), data.getRoom());
        log.info("{} joined the room.", data.getUsername());
        if (Objects.equals(room, data.getRoom())) {
            List<String> updated = new ArrayList<>(users);
            updated.add(data.getUsername());
            users = updated;
        }
    }

    private synchronized void handleLeave(LeaveData data) {
        log.debug("Command: {} User: {} Room: {}", data.getCommand(), data.getUsername(), data.getRoom());
        log.info("{} left the room.", data.getUsername());
        if (Objects.equals(room, data.getRoom())) {
            List<String> updated = new ArrayList<>(users);
            updated.remove(data.getUsername());
            users = updated;
        }
    }

    private void joinRoom() {
        if (username != null && room != null) {
            send(new JoinData(username, room, password));
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
        } catch (Exception e) {
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
        if (expectedState == null) {
            return true;
        }

        if (localStatus.getPlaybackState() != expectedState.getPlaybackState()) {
            // Status changed
            return true;
        }

        if (Math.abs(localStatus.getPosition() - expectedState.getPosition()) <= MIN_PROG_CHANGE) {
            // Progress change is not significant enough
            return false;
        }

        long currentTime = getCurrentTime();
        long timeDiff = currentTime - expectedState.getLastUpdateTime();

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
        double actualAdvance = (localStatus.getPosition() - expectedState.getPosition()) * 1000; // in milliseconds
        double relativeError = Math.abs(actualAdvance - expectedAdvance) / expectedAdvance;

        // Progress change does not match expected change based on playback speed
        return relativeError > DRIFT_THRESHOLD;
    }

    /**
     * Records playback state expected on the server-side.
     */
    private void updateServerState(PlayerState serverStatus) {
        PlayerState expectation = new PlayerState(Objects.requireNonNull(serverStatus, "serverStatus"));
        expectation.setLastUpdateTime(getCurrentTime());
        expectedState = expectation;
    }

    @Override
    public synchronized void close() {
        try {
            if (username != null && room != null) {
                send(new LeaveData(username, room, password));
            }
        } finally {
            super.close();
        }
    }


}
