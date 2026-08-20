package io.github.syncnuke.client;

import io.github.syncnuke.player.PlayerManager;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SyncManagerTest {

    @Mock
    private PlayerManager videoPlayer;

    private SyncManager manager;

    @BeforeEach
    void setUp() throws Exception {
        resetSingleton();
    }

    @AfterEach
    void tearDown() throws Exception {
        SyncManager current = getSingleton();
        if (current != null) {
            Field executorField =
                    SyncManager.class.getDeclaredField("syncExecutor");
            executorField.setAccessible(true);
            ExecutorService executor =
                    (ExecutorService) executorField.get(current);
            executor.shutdownNow();
        }
        resetSingleton();
    }

    @Test
    void getInstance_registersListenerOnVideoPlayer() {
        manager = SyncManager.getInstance(videoPlayer);

        verify(videoPlayer).setListener(same(manager));
    }

    @Test
    void onStatusChange_forwardsExactSnapshotWithoutReadingVideoPlayer()
            throws Exception {
        manager = SyncManager.getInstance(videoPlayer);
        SyncClient<?> syncClient = mock(SyncClient.class);
        setSyncClient(manager, syncClient);
        clearInvocations(videoPlayer);

        PlayerState status = new PlayerState();
        status.setPlaybackState(PlaybackState.PLAYING);
        status.setPosition(12.5);

        manager.onStatusChange(status);

        verify(syncClient, timeout(1000)).onStatusChange(same(status));
        verifyNoInteractions(videoPlayer);
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
