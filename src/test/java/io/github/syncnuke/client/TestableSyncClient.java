package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.VideoPlayer;
import io.github.syncnuke.player.VideoPlayerEventListener;
import lombok.experimental.Delegate;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class TestableSyncClient extends SyncClient<Object> {

    @Delegate
    private final VideoPlayerEventListener listener;
    private final NetClient<Object> netClient;
    private final AtomicInteger keepAliveCount = new AtomicInteger();
    private final AtomicLong currentTime = new AtomicLong();

    TestableSyncClient(int keepAliveInterval,
                       VideoPlayer player,
                       VideoPlayerEventListener listener,
                       NetClient<Object> netClient) {
        super(keepAliveInterval, player);
        this.listener = listener;
        this.netClient = netClient;
        this.currentTime.set(System.currentTimeMillis());
    }

    @Override
    protected long getCurrentTime() {
        return currentTime.get();
    }

    /**
     * Advances the mock time by the specified number of milliseconds.
     */
    public void advanceTimeBy(long milliseconds) {
        currentTime.addAndGet(milliseconds);
    }

    /**
     * Sets the mock time to a specific value.
     */
    public void setCurrentTime(long timeMillis) {
        currentTime.set(timeMillis);
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
