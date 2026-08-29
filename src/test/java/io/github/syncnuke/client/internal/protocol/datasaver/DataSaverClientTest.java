package io.github.syncnuke.client.internal.protocol.datasaver;

import io.github.syncnuke.client.internal.protocol.datasaver.data.StateData;
import io.github.syncnuke.client.internal.protocol.datasaver.data.Command;
import io.github.syncnuke.client.internal.protocol.datasaver.data.BaseData;
import io.github.syncnuke.client.internal.protocol.datasaver.data.JoinData;
import io.github.syncnuke.client.internal.protocol.datasaver.data.State;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.PlayerManager;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSaverClientTest {

    @Mock
    private PlayerManager videoPlayer;
    @Mock
    private NetClient<BaseData> netClient;
    @Mock
    private TimingService timingService;
    @Mock
    private ScheduledFuture<?> keepAliveFuture;

    private final AtomicLong currentTime = new AtomicLong(1000);
    private final AtomicReference<PlayerState> playerStatus =
            new AtomicReference<>();

    private DataSaverClient client;
    private Runnable keepAliveTask;

    @BeforeEach
    void setUp() {
        playerStatus.set(state(PlaybackState.PAUSED, 0.0, 1.0));
        lenient().when(videoPlayer.getStatus())
                .thenAnswer(invocation -> playerStatus.get().copy());
        lenient().when(timingService.getCurrentTime())
                .thenAnswer(invocation -> currentTime.get());
        when(timingService.schedule(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class)
        )).thenAnswer(invocation -> keepAliveFuture);

        client = new DataSaverClient(
                "localhost",
                8080,
                0,
                10000,
                videoPlayer,
                netClient,
                timingService
        );

        ArgumentCaptor<Runnable> taskCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(timingService).schedule(
                taskCaptor.capture(),
                eq(10000L),
                eq(10000L),
                eq(TimeUnit.MILLISECONDS)
        );
        keepAliveTask = taskCaptor.getValue();
    }

    @Test
    void loginJoinsRoom() {
        client.login("user", "room");

        ArgumentCaptor<JoinData> messageCaptor =
                ArgumentCaptor.forClass(JoinData.class);
        verify(netClient).send(messageCaptor.capture());
        JoinData message = messageCaptor.getValue();
        assertEquals(Command.JOIN_ROOM, message.getCommand());
        assertEquals("room", message.getRoom());
    }

    @Test
    void inboundStateIsHandledWhileLocalSendIsInProgress() {
        StateData inbound = new StateData(
                Command.UPDATE_STATE,
                State.PLAYING,
                15.0,
                1.25
        );
        doAnswer(invocation -> {
            client.handleResponse(inbound);
            return null;
        }).when(netClient).send(any(StateData.class));

        client.onStatusChange(
                state(PlaybackState.PLAYING, 5.0, 1.0)
        );

        ArgumentCaptor<PlayerState> targetCaptor =
                ArgumentCaptor.forClass(PlayerState.class);
        verify(videoPlayer).updateStatus(targetCaptor.capture());
        assertEquals(
                PlaybackState.PLAYING,
                targetCaptor.getValue().getPlaybackState()
        );
        assertEquals(15.0, targetCaptor.getValue().getPosition());
        assertEquals(1.25, targetCaptor.getValue().getPlaybackSpeed());
    }

    @Test
    void inboundStatePreservesFieldsMissingFromWireFormat() {
        PlayerState current = state(PlaybackState.PAUSED, 3.0, 1.5);
        current.setLastUpdateTime(123);
        playerStatus.set(current);

        client.handleResponse(new StateData(
                Command.UPDATE_STATE,
                State.PLAYING,
                9.0,
                2.0
        ));

        ArgumentCaptor<PlayerState> targetCaptor =
                ArgumentCaptor.forClass(PlayerState.class);
        verify(videoPlayer).updateStatus(targetCaptor.capture());
        PlayerState target = targetCaptor.getValue();
        assertEquals(PlaybackState.PLAYING, target.getPlaybackState());
        assertEquals(9.0, target.getPosition());
        assertEquals(2.0, target.getPlaybackSpeed());
        assertEquals(123, target.getLastUpdateTime());
    }

    @Test
    void serverAppliedStatusIsNotEchoed() {
        PlayerState expected = state(PlaybackState.PAUSED, 10.0, 1.0);
        playerStatus.set(expected);
        client.handleResponse(new StateData(
                Command.UPDATE_STATE,
                State.PAUSED,
                10.0,
                1.0
        ));
        clearInvocations(netClient);

        client.onStatusChange(expected.copy());

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void keepAliveSendsUnchangedStatusAtExactInterval() {
        PlayerState expected = state(PlaybackState.PAUSED, 10.0, 1.0);
        playerStatus.set(expected);
        client.handleResponse(new StateData(
                Command.UPDATE_STATE,
                State.PAUSED,
                10.0,
                1.0
        ));
        client.sendKeepAlive();
        clearInvocations(netClient);

        currentTime.addAndGet(10000);
        keepAliveTask.run();

        ArgumentCaptor<StateData> messageCaptor =
                ArgumentCaptor.forClass(StateData.class);
        verify(netClient).send(messageCaptor.capture());
        assertEquals(Command.UPDATE_STATE, messageCaptor.getValue().getCommand());
        assertEquals(State.PAUSED, messageCaptor.getValue().getState());
        assertEquals(10.0, messageCaptor.getValue().getPosition());
        assertEquals(1.0, messageCaptor.getValue().getPlaybackSpeed());
    }

    @Test
    void zeroKeepAliveIntervalDisablesScheduling() {
        clearInvocations(timingService);
        @SuppressWarnings("unchecked")
        NetClient<BaseData> zeroIntervalNetClient = mock(NetClient.class);

        new DataSaverClient(
                "localhost",
                8080,
                0,
                0,
                videoPlayer,
                zeroIntervalNetClient,
                timingService
        );

        verify(timingService, never()).schedule(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class)
        );
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
