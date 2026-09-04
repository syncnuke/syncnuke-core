package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;

import java.util.concurrent.atomic.AtomicInteger;

final class TestableSyncClient extends SyncClient<Object> {

    private final NetClient<Object> netClient;
    private final AtomicInteger keepAliveCount = new AtomicInteger();

    TestableSyncClient(int keepAliveInterval,
                       PlayerManager videoPlayer,
                       NetClient<Object> netClient, TimingService timingService) {
        super(keepAliveInterval, videoPlayer, timingService);
        this.netClient = netClient;
    }

    @Override
    protected NetClient<Object> getNetClient() {
        return netClient;
    }

    @Override
    public void login(String username, String room) {
        throw new UnsupportedOperationException("Should never be accessed during SyncClient testing");
    }

    @Override
    public RoomInfo getRoomInfo() {
        throw new UnsupportedOperationException("Should never be accessed during SyncClient testing");
    }

    @Override
    protected void handleResponse(Object data) {
        if (data instanceof PlayerState) {
            getPlayer().updateStatus((PlayerState) data);
        }
    }

    @Override
    public void onStatusChange(PlayerState localStatus) {
        send(localStatus);
    }

    @Override
    protected void sendKeepAlive() {
        keepAliveCount.incrementAndGet();
    }

}
