package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.VideoPlayer;
import io.github.syncnuke.player.VideoPlayerEventListener;
import lombok.experimental.Delegate;

import java.util.concurrent.atomic.AtomicInteger;

final class TestableSyncClient extends SyncClient<Object> {

    @Delegate
    private final VideoPlayerEventListener listener;
    private final NetClient<Object> netClient;
    private final AtomicInteger keepAliveCount = new AtomicInteger();

    TestableSyncClient(int keepAliveInterval,
                       VideoPlayer player,
                       VideoPlayerEventListener listener,
                       NetClient<Object> netClient) {
        super(keepAliveInterval, player);
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
