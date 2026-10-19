package io.github.syncnuke.client;

import io.github.syncnuke.player.MediaPlayer;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import io.github.syncnuke.client.internal.net.NetClient;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static io.github.syncnuke.client.ClientTestSupport.awaitIdle;
import static org.mockito.ArgumentMatchers.same;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncManagerTest {

    @Mock
    private MediaPlayer mediaPlayer;

    private SyncManager manager;

    @BeforeEach
    void setUp() throws Exception {
        resetSingleton();
        when(mediaPlayer.getStatus()).thenReturn(new PlayerState());
    }

    @AfterEach
    void tearDown() throws Exception {
        SyncManager current = getSingleton();
        if (current != null) {
            current.close();
        }
        resetSingleton();
    }

    @Test
    void getInstance_wrapsMediaPlayerAndRegistersInternalListener() throws Exception {
        manager = SyncManager.getInstance(mediaPlayer, 0);

        verify(mediaPlayer).getStatus();
        PlayerManager playerManager = getPlayerManager(manager);
        Field listenerField = PlayerManager.class.getDeclaredField("eventListener");
        listenerField.setAccessible(true);
        assertSame(manager, listenerField.get(playerManager));
    }

    @Test
    void onStatusChange_forwardsExactSnapshotWithoutReadingMediaPlayer()
            throws Exception {
        manager = SyncManager.getInstance(mediaPlayer, 0);
        SyncClient<?> syncClient = mock(SyncClient.class);
        setSyncClient(manager, syncClient);
        clearInvocations(mediaPlayer);

        PlayerState status = new PlayerState();
        status.setPlaybackState(PlaybackState.PLAYING);
        status.setPosition(12.5);

        manager.onStatusChange(status);

        verify(syncClient, timeout(1000)).onStatusChange(same(status));
        verifyNoInteractions(mediaPlayer);
    }

    @Test
    void stopClosesClientQueueAndStopsForwardingPlayerEvents() throws Exception {
        manager = SyncManager.getInstance(mediaPlayer, 0);
        @SuppressWarnings("unchecked")
        NetClient<Object> netClient = mock(NetClient.class);
        TestableSyncClient client = new TestableSyncClient(
                0, getPlayerManager(manager), netClient, new TestableTimingService());
        setSyncClient(manager, client);

        manager.stop();
        manager.stop();
        manager.onStatusChange(new PlayerState());
        awaitIdle(client);

        verify(netClient).close();
        assertTrue(ClientTestSupport.executor(client, "eventQueue").isTerminated());
    }

    @Test
    void stopCancelsPendingStartupAndCloseRejectsNewStartup() throws Exception {
        manager = SyncManager.getInstance(mediaPlayer, 0);
        Field executorField = SyncManager.class.getDeclaredField("syncExecutor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(manager);
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(busy.await(1, TimeUnit.SECONDS));
            manager.start("unsupported", "localhost", 8080, "user", "room", null);
            Field startupField = SyncManager.class.getDeclaredField("startupTask");
            startupField.setAccessible(true);
            Future<?> startup = (Future<?>) startupField.get(manager);
            manager.stop();
            assertTrue(startup.isCancelled());
            assertNull(startupField.get(manager));
        } finally {
            release.countDown();
        }
        executor.submit(() -> {}).get(1, TimeUnit.SECONDS);
        manager.close();
        manager.start("unsupported", "localhost", 8080, "user", "room", null);
        assertTrue(executor.isShutdown());
    }

    private static PlayerManager getPlayerManager(SyncManager syncManager)
            throws Exception {
        Field playerManagerField = SyncManager.class.getDeclaredField("playerManager");
        playerManagerField.setAccessible(true);
        return (PlayerManager) playerManagerField.get(syncManager);
    }

    private static void setSyncClient(
            SyncManager syncManager,
            SyncClient<?> syncClient
    ) throws Exception {
        Field clientField = SyncManager.class.getDeclaredField("syncClient");
        clientField.setAccessible(true);
        clientField.set(syncManager, syncClient);
    }

    private static SyncManager getSingleton() throws Exception {
        Field instanceField = SyncManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        return (SyncManager) instanceField.get(null);
    }

    private static void resetSingleton() throws Exception {
        Field instanceField = SyncManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }
}
