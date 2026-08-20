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
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class DataSaverClient extends SyncClient<BaseData> {

    private final NetClient<BaseData> netClient;

    private final AtomicBoolean ignoreUpdates = new AtomicBoolean(false);
    private final int debounceDelay; // in milliseconds

    public DataSaverClient(String host, int port, int debounceDelay, PlayerManager videoPlayer) {
        super(10000, videoPlayer);
        this.netClient = new TcpClient<>();
        connect(host, port, new BaseCodec());
        this.debounceDelay = debounceDelay;
    }

    public DataSaverClient(String host, int port, PlayerManager videoPlayer) {
        this(host, port, 0, videoPlayer);
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
        sendState(status);
    }

    @Override
    protected void sendKeepAlive() {
        sendState(getPlayer().getStatus());
    }

    private State getState(PlayerState status) {
        return status.getPlaybackState() == PlaybackState.PAUSED ? State.PAUSED : State.PLAYING;
    }

    private void sendState(PlayerState status) {
        if (!isSignificantChange(status)) {
            return;
        }

        try {
            State state = getState(status);
            BaseData message = new BaseData(
                    Command.UPDATE_STATE,
                    state,
                    status.getPosition()
            );

            ignoreUpdates.set(true);
            log.info("Sending state: status={}, progress={}", state, status.getPosition());
            send(message);
            Thread.sleep(debounceDelay);
        } catch (InterruptedException e) {
            log.error("Failed to send state: {}", e.getMessage());
        } finally {
            ignoreUpdates.set(false);
        }
    }

}
