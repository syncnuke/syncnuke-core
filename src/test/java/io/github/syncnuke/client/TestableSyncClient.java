package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.PlayerManager;
import io.github.syncnuke.player.VideoPlayerEventListener;
import io.github.syncnuke.service.TimingService;
import lombok.experimental.Delegate;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class TestableSyncClient extends SyncClient<Object> {

    @Delegate
    private final VideoPlayerEventListener listener;
    private final NetClient<Object> netClient;
    private final AtomicInteger keepAliveCount = new AtomicInteger();

    TestableSyncClient(int keepAliveInterval,
                       PlayerManager videoPlayer,
                       VideoPlayerEventListener listener,
                       NetClient<Object> netClient, TimingService timingService) {
        super(keepAliveInterval, videoPlayer, timingService);
        this.listener = listener;
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
    protected void handleResponse(Object data) {
        // Do nothing, used only for verification through spying in SyncClient testing
    }

    @Override
    protected void sendKeepAlive() {
        keepAliveCount.incrementAndGet();
    }

}
