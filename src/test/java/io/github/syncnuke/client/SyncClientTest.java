package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.Codec;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.PlayerManager;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
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
    PlayerManager videoPlayer;
    @Mock
    NetClient<Object> netClient;
    @Mock
    Codec<Object> codec;

    TestableTimingService timingService;
    TestableSyncClient client;

    @BeforeEach
    void setUp() {
        timingService = new TestableTimingService();
        client = spy(new TestableSyncClient(0, videoPlayer, netClient, timingService));
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

    @Test
    void isSignificantChange_returnsTrueBeforeServerExpectationIsRecorded() {
        PlayerState localStatus = state(PlaybackState.PAUSED, 0.0, 1.0);

        assertTrue(client.isSignificantChange(localStatus));
    }

    @Test
    void isSignificantChange_returnsTrueWhenPlaybackStateChanged() {
        client.updateServerState(
                state(PlaybackState.PAUSED, 0.0, 1.0)
        );

        boolean result = client.isSignificantChange(
                state(PlaybackState.PLAYING, 0.0, 1.0)
        );

        assertTrue(result);
    }

    @Test
    void isSignificantChange_returnsFalseWhenProgressChangeBelowThreshold() {
        client.updateServerState(
                state(PlaybackState.PLAYING, 10.0, 1.0)
        );

        boolean result = client.isSignificantChange(
                state(PlaybackState.PLAYING, 10.3, 1.0)
        );

        assertFalse(result);
    }

    @Test
    void isSignificantChange_returnsTrueWhenPausedAndSeeking() {
        client.updateServerState(
                state(PlaybackState.PAUSED, 10.0, 1.0)
        );

        boolean result = client.isSignificantChange(
                state(PlaybackState.PAUSED, 11.1, 1.0)
        );

        assertTrue(result);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1})
    void isSignificantChange_returnsTrueWhenPlaybackSpeedIsInvalid(
            double speed
    ) {
        client.updateServerState(
                state(PlaybackState.PLAYING, 0.0, 1.0)
        );
        timingService.advanceTimeBy(1);

        boolean result = client.isSignificantChange(
                state(PlaybackState.PLAYING, 0.6, speed)
        );

        assertTrue(result);
    }

    @Test
    void isSignificantChange_returnsTrueWhenSeekDiffersFromProgression() {
        client.updateServerState(
                state(PlaybackState.PLAYING, 0.0, 1.0)
        );
        timingService.advanceTimeBy(600);

        boolean result = client.isSignificantChange(
                state(PlaybackState.PLAYING, 2.0, 1.0)
        );

        assertTrue(result);
    }

    @Test
    void isSignificantChange_returnsFalseWhenExpectedAdvanceIsTooSmall() {
        client.updateServerState(
                state(PlaybackState.PLAYING, 0.0, 1.0)
        );
        timingService.advanceTimeBy(1);

        boolean result = client.isSignificantChange(
                state(PlaybackState.PLAYING, 10.0, 0.000000000001)
        );

        assertFalse(result);
    }

    @Test
    void isSignificantChange_returnsFalseForOrdinaryProgression() {
        client.updateServerState(
                state(PlaybackState.PLAYING, 0.0, 1.0)
        );
        timingService.advanceTimeBy(600);

        boolean result = client.isSignificantChange(
                state(PlaybackState.PLAYING, 0.6, 1.0)
        );

        assertFalse(result);
    }

    @Test
    void handleResponse_recordsExpectationBeforeApplyingStatus() {
        PlayerState serverStatus =
                state(PlaybackState.PAUSED, 10.0, 1.0);
        doAnswer(invocation -> {
            assertFalse(client.isSignificantChange(serverStatus.copy()));
            return null;
        }).when(videoPlayer).updateStatus(serverStatus);

        client.handleResponse(serverStatus);

        verify(videoPlayer).updateStatus(same(serverStatus));
    }

    @Test
    void localStatusCanSendWithoutChangingServerExpectation() {
        PlayerState serverStatus =
                state(PlaybackState.PAUSED, 10.0, 1.0);
        client.updateServerState(serverStatus);
        PlayerState localStatus =
                state(PlaybackState.PLAYING, 20.0, 1.0);

        client.onStatusChange(localStatus);

        verify(netClient).send(same(localStatus));
        assertFalse(client.isSignificantChange(serverStatus.copy()));
    }

    @Test
    void sendsAndKeepAliveDoNotChangeServerExpectation() {
        PlayerState serverStatus =
                state(PlaybackState.PAUSED, 10.0, 1.0);
        client.updateServerState(serverStatus);

        client.send(new Object());
        client.sendKeepAlive();

        assertFalse(client.isSignificantChange(serverStatus.copy()));
    }

    @Test
    void recordServerExpectation_keepsAnOwnedSnapshot() {
        PlayerState serverStatus =
                state(PlaybackState.PAUSED, 10.0, 1.0);
        client.updateServerState(serverStatus);

        serverStatus.setPlaybackState(PlaybackState.PLAYING);
        serverStatus.setPosition(99.0);

        assertFalse(client.isSignificantChange(
                state(PlaybackState.PAUSED, 10.0, 1.0)
        ));
    }

    private static PlayerState state(
            PlaybackState playbackState,
            double position,
            double playbackSpeed
    ) {
        PlayerState status = new PlayerState();
        status.setPlaybackState(playbackState);
        status.setPosition(position);
        status.setPlaybackSpeed(playbackSpeed);
        return status;
    }

}
