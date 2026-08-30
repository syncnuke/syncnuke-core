package io.github.syncnuke.client.internal.protocol.syncplay;

import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.protocol.syncplay.service.DataProcessor;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.syncplay.proto.SyncplayProto.PlayState;
import pl.syncplay.proto.SyncplayProto.StateMessage;
import pl.syncplay.proto.SyncplayProto.SyncplayMessage;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncplayClientTest {

    @Mock
    private PlayerManager videoPlayer;
    @Mock
    private NetClient<SyncplayMessage> netClient;
    @Mock
    private TimingService timingService;
    @Mock
    private ScheduledFuture<?> keepAliveFuture;

    private final AtomicReference<PlayerState> playerStatus =
            new AtomicReference<>();

    private SyncplayClient client;

    @BeforeEach
    void setUp() {
        playerStatus.set(state(PlaybackState.PAUSED, 0.0, 1.0));
        lenient().when(videoPlayer.getStatus())
                .thenAnswer(invocation -> playerStatus.get().copy());
        when(timingService.getCurrentTime()).thenReturn(1000L);
        when(timingService.schedule(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class)
        )).thenAnswer(invocation -> keepAliveFuture);

        client = new SyncplayClient(
                new DataProcessor(),
                "localhost",
                8999,
                videoPlayer,
                netClient,
                timingService,
                timingService
        );
        client.login("local-user", "room");
        clearInvocations(videoPlayer, netClient);
    }

//    @Test
//    void inboundSeekIsAppliedAcknowledgedAndNotEchoed() {
//        client.handleResponse(
//                stateMessage(false, 12.0, true, "remote-user")
//        );
//
//        ArgumentCaptor<PlayerState> targetCaptor =
//                ArgumentCaptor.forClass(PlayerState.class);
//        verify(videoPlayer).updateStatus(targetCaptor.capture());
//        PlayerState target = targetCaptor.getValue();
//        assertEquals(PlaybackState.PLAYING, target.getPlaybackState());
//        assertEquals(12.0, target.getPosition());
//
//        ArgumentCaptor<SyncplayMessage> responseCaptor =
//                ArgumentCaptor.forClass(SyncplayMessage.class);
//        verify(netClient, times(2)).send(responseCaptor.capture());
//        List<SyncplayMessage> responses = responseCaptor.getAllValues();
//        assertTrue(responses.get(0).getState().getPlaystate().getDoSeek());
//        assertFalse(responses.get(1).getState().getPlaystate().getDoSeek());
//
//        playerStatus.set(target.copy());
//        client.sendKeepAlive();
//        clearInvocations(netClient);
//
//        client.onStatusChange(target.copy());
//
//        verify(netClient, times(0)).send(any(SyncplayMessage.class));
//    }

    @Test
    void inboundStateWithoutDoSeekPreservesLocalPosition() {
        playerStatus.set(state(PlaybackState.PAUSED, 3.0, 1.0));

        client.handleResponse(
                stateMessage(false, 20.0, false, "remote-user")
        );

        ArgumentCaptor<PlayerState> targetCaptor =
                ArgumentCaptor.forClass(PlayerState.class);
        verify(videoPlayer).updateStatus(targetCaptor.capture());
        PlayerState target = targetCaptor.getValue();
        assertEquals(PlaybackState.PLAYING, target.getPlaybackState());
        assertEquals(3.0, target.getPosition());
    }

    @Test
    void localEventsSetDoSeekOnlyForPositionChanges() {
        client.onStatusChange(
                state(PlaybackState.PAUSED, 8.0, 1.0)
        );

        ArgumentCaptor<SyncplayMessage> messageCaptor =
                ArgumentCaptor.forClass(SyncplayMessage.class);
        verify(netClient).send(messageCaptor.capture());
        assertTrue(
                messageCaptor.getValue()
                        .getState()
                        .getPlaystate()
                        .getDoSeek()
        );

        clearInvocations(netClient);
        client.onStatusChange(
                state(PlaybackState.PLAYING, 8.0, 1.0)
        );

        verify(netClient).send(messageCaptor.capture());
        assertFalse(
                messageCaptor.getValue()
                        .getState()
                        .getPlaystate()
                        .getDoSeek()
        );
    }

    private static SyncplayMessage stateMessage(
            boolean paused,
            double position,
            boolean doSeek,
            String setBy
    ) {
        PlayState playState = PlayState.newBuilder()
                .setPaused(paused)
                .setPosition(position)
                .setDoSeek(doSeek)
                .setSetBy(setBy)
                .build();
        StateMessage state = StateMessage.newBuilder()
                .setPlaystate(playState)
                .build();
        return SyncplayMessage.newBuilder()
                .setState(state)
                .build();
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
