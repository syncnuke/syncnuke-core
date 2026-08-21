package io.github.syncnuke.client.internal.datasaver;

import io.github.syncnuke.client.SyncClient;
import io.github.syncnuke.client.internal.datasaver.data.BaseCodec;
import io.github.syncnuke.client.internal.datasaver.data.BaseData;
import io.github.syncnuke.client.internal.datasaver.data.Command;
import io.github.syncnuke.client.internal.datasaver.data.State;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.net.TcpClient;
import io.github.syncnuke.player.PlayerManager;
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
        // TODO: Implement room logic in datasaver stack
    }

    @Override
    protected void handleResponse(BaseData data) {
        try {
            Command command = Objects.requireNonNull(data.getCommand());
            if (command == Command.UPDATE_STATE) {
                handleStateUpdate(data);
            }
        } catch (Exception e) {
            log.error("Error processing server response: {}", e.getMessage());
        }
    }

    private void handleStateUpdate(BaseData data) {
        PlayerState serverStatus = getPlayer().getStatus();
        serverStatus.setPlaybackState(
                data.getState() == State.PAUSED
                        ? PlaybackState.PAUSED
                        : PlaybackState.PLAYING
        );
        serverStatus.setPosition(data.getPosition());

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
            BaseData message = new BaseData(
                    Command.UPDATE_STATE,
                    state,
                    status.getPosition()
            );

            log.info("Sending state: status={}, progress={}", state, status.getPosition());
            send(message);
            Thread.sleep(debounceDelay);
        } catch (InterruptedException e) {
            log.error("Failed to send state: {}", e.getMessage());
        }
    }

}
