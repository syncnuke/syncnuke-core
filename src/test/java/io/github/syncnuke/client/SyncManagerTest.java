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

import static org.junit.jupiter.api.Assertions.assertSame;
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
