package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.Codec;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.VideoPlayer;
import io.github.syncnuke.player.VideoPlayerEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncClientTest {

    @Mock
    VideoPlayer player;
    @Mock
    VideoPlayerEventListener listener;
    @Mock
    NetClient<Object> netClient;
    @Mock
    Codec<Object> codec;

    TestableSyncClient client;

    @BeforeEach
    void setUp() {
        client = spy(new TestableSyncClient(0, player, listener, netClient));
    }

    @Test
    void connect_CallsNetClientConnectAsExpected() {
        client.connect("localhost", 8080, codec);

        verify(netClient).connect("localhost", 8080, codec);
        verify(netClient).addListener(any());
    }

    @Test
    void send_SkipsSend_WhenDataIsNull() {
        client.send(null);

        verify(netClient, never()).send(any());
    }

    @Test
    void send_DelegatesToNetClient_WhenDataIsNotNull() {
        Object payload = new Object();
        client.send(payload);

        verify(netClient).send(payload);
    }

    @ParameterizedTest
    @ValueSource(ints = {SyncClient.PLAY_STATUS, SyncClient.PAUSE_STATUS})
    void isSignificantChange_ReturnsTrue_BeforeUpdateTrackingIsCalled(int status) {
        verify(client, never()).updateTracking(anyInt(), anyDouble());

        boolean result = client.isSignificantChange(status, 0.0);

        assertTrue(result);
    }

    @Test
    void isSignificantChange_ReturnsTrue_WhenStatusChanged() {
        client.updateTracking(SyncClient.PAUSE_STATUS, 0.0);

        boolean result = client.isSignificantChange(SyncClient.PLAY_STATUS, 0.0);

        assertTrue(result);
    }

    @Test
    void isSignificantChange_ReturnsFalse_WhenProgressChangeBelowThreshold() {
        client.updateTracking(SyncClient.PLAY_STATUS, 10.0);

        boolean result = client.isSignificantChange(SyncClient.PLAY_STATUS, 10.3);

        assertFalse(result);
    }

    @Test
    void isSignificantChange_ReturnsTrue_WhenPausedAndSeeking() {
        client.updateTracking(SyncClient.PAUSE_STATUS, 10.0);

        boolean result = client.isSignificantChange(SyncClient.PAUSE_STATUS, 11.1);

        assertTrue(result);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1})
    void isSignificantChange_ReturnsTrue_WhenPlaybackSpeedIsInvalid(double speed) {
        when(player.getPlaybackSpeed()).thenReturn(speed);
        client.updateTracking(SyncClient.PLAY_STATUS, 0.0);

        client.advanceTimeBy(1);
        boolean result = client.isSignificantChange(SyncClient.PLAY_STATUS, 0.6);

        assertTrue(result);
    }

    @Test
    void isSignificantChange_ReturnsTrue_WhenDriftExceedsThreshold() {
        when(player.getPlaybackSpeed()).thenReturn(1.0);
        client.updateTracking(SyncClient.PLAY_STATUS, 0.0);
        client.advanceTimeBy(1);

        boolean result = client.isSignificantChange(SyncClient.PLAY_STATUS, 0.51);

        assertTrue(result);
    }

    @Test
    void isSignificantChange_ReturnsFalse_WhenExpectedAdvanceIsTooSmall() {
        when(player.getPlaybackSpeed()).thenReturn(0.000000000001); // Cause multiplication to lead to 0 millis
        client.updateTracking(SyncClient.PLAY_STATUS, 0.0);
        client.advanceTimeBy(1);

        boolean result = client.isSignificantChange(SyncClient.PLAY_STATUS, 10.0);

        assertFalse(result);
    }

    @Test
    void isSignificantChange_ReturnsFalse_WhenDriftUnderThreshold() {
        when(player.getPlaybackSpeed()).thenReturn(1.0);
        client.updateTracking(SyncClient.PLAY_STATUS, 0.0);

        client.advanceTimeBy(600);
        boolean result = client.isSignificantChange(SyncClient.PLAY_STATUS, 0.6);

        assertFalse(result);
    }

}
