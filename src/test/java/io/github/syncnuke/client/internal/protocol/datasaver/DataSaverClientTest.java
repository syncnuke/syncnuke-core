package io.github.syncnuke.client.internal.protocol.datasaver;

import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.protocol.datasaver.data.*;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.service.TimingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    void loginJoinsRoomWithPassword() {
        client.login("user", "room", "password");

        ArgumentCaptor<JoinData> messageCaptor =
                ArgumentCaptor.forClass(JoinData.class);
        verify(netClient).send(messageCaptor.capture());
        JoinData message = messageCaptor.getValue();
        assertEquals(Command.JOIN_ROOM, message.getCommand());
        assertEquals("user", message.getUsername());
        assertEquals("room", message.getRoom());
        assertEquals("password", message.getPassword());
    }

    @Test
    void connectRedirectReconnectsAndRejoinsRoomWithPassword() {
        client.login("user", "room", "password");
        clearInvocations(netClient);

        client.handleResponse(new ConnectData("next.example", 27032));

        InOrder redirect = inOrder(netClient);
        redirect.verify(netClient).connect(eq("next.example"), eq(27032), any(BaseCodec.class));
        ArgumentCaptor<JoinData> messageCaptor = ArgumentCaptor.forClass(JoinData.class);
        redirect.verify(netClient).send(messageCaptor.capture());
        assertEquals(Command.JOIN_ROOM, messageCaptor.getValue().getCommand());
        assertEquals("user", messageCaptor.getValue().getUsername());
        assertEquals("room", messageCaptor.getValue().getRoom());
        assertEquals("password", messageCaptor.getValue().getPassword());
    }

    @Test
    void closeLeavesRoomWithPassword() {
        client.login("user", "room", "password");
        clearInvocations(netClient);

        client.close();

        ArgumentCaptor<LeaveData> messageCaptor =
                ArgumentCaptor.forClass(LeaveData.class);
        verify(netClient).send(messageCaptor.capture());
        assertEquals("user", messageCaptor.getValue().getUsername());
        assertEquals("room", messageCaptor.getValue().getRoom());
        assertEquals("password", messageCaptor.getValue().getPassword());
    }

    @Test
    void roomInfoTracksJoinsAndLeaves() {
        client.login("first", "room", null);
        client.handleResponse(new JoinData("second", "room", null));

        assertEquals("room", client.getRoomInfo().getRoom());
        assertEquals(
                Arrays.asList("first", "second"),
                client.getRoomInfo().getUsers()
        );

        client.handleResponse(new LeaveData("second", "room", null));

        assertEquals(
                Collections.singletonList("first"),
                client.getRoomInfo().getUsers()
        );
    }

    @Test
    void significantChangeIsSentBeforeServerExpectationIsRecorded() {
        PlayerState localStatus = state(PlaybackState.PAUSED, 0.0, 1.0);

        client.onStatusChange(localStatus);

        verify(netClient).send(any(StateData.class));
    }

    @Test
    void sendFailureDoesNotEscapeStatusCallback() {
        doThrow(new IllegalStateException("send failed"))
                .when(netClient).send(any(StateData.class));

        assertDoesNotThrow(() -> client.onStatusChange(
                state(PlaybackState.PAUSED, 0.0, 1.0)
        ));
    }

    @Test
    void playbackStateChangeIsSent() {
        recordServerState(PlaybackState.PAUSED, 0.0, 1.0);

        client.onStatusChange(state(PlaybackState.PLAYING, 0.0, 1.0));

        verify(netClient).send(any(StateData.class));
    }

    @Test
    void progressChangeBelowThresholdIsNotSent() {
        recordServerState(PlaybackState.PLAYING, 10.0, 1.0);

        client.onStatusChange(state(PlaybackState.PLAYING, 10.3, 1.0));

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void pausedSeekIsSent() {
        recordServerState(PlaybackState.PAUSED, 10.0, 1.0);

        client.onStatusChange(state(PlaybackState.PAUSED, 11.1, 1.0));

        verify(netClient).send(any(StateData.class));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1})
    void progressChangeWithInvalidPlaybackSpeedIsSent(double speed) {
        recordServerState(PlaybackState.PLAYING, 0.0, 1.0);
        currentTime.incrementAndGet();

        client.onStatusChange(state(PlaybackState.PLAYING, 0.6, speed));

        verify(netClient).send(any(StateData.class));
    }

    @Test
    void seekThatDiffersFromExpectedProgressionIsSent() {
        recordServerState(PlaybackState.PLAYING, 0.0, 1.0);
        currentTime.addAndGet(600);

        client.onStatusChange(state(PlaybackState.PLAYING, 2.0, 1.0));

        verify(netClient).send(any(StateData.class));
    }

    @Test
    void changeWithExpectedAdvanceTooSmallIsNotSent() {
        recordServerState(PlaybackState.PLAYING, 0.0, 1.0);
        currentTime.incrementAndGet();

        client.onStatusChange(state(
                PlaybackState.PLAYING,
                10.0,
                0.000000000001
        ));

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void ordinaryProgressionIsNotSent() {
        recordServerState(PlaybackState.PLAYING, 0.0, 1.0);
        currentTime.addAndGet(600);

        client.onStatusChange(state(PlaybackState.PLAYING, 0.6, 1.0));

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void serverExpectationIsRecordedBeforeStatusIsApplied() {
        PlayerState expected = state(PlaybackState.PAUSED, 10.0, 1.0);
        playerStatus.set(expected);
        doAnswer(invocation -> {
            client.onStatusChange(expected.copy());
            verify(netClient, never()).send(any(StateData.class));
            return null;
        }).when(videoPlayer).updateStatus(any(PlayerState.class));

        client.handleResponse(stateData(PlaybackState.PAUSED, 10.0, 1.0));

        verify(videoPlayer).updateStatus(any(PlayerState.class));
    }

    @Test
    void localSendDoesNotChangeServerExpectation() {
        recordServerState(PlaybackState.PAUSED, 10.0, 1.0);

        client.onStatusChange(state(PlaybackState.PLAYING, 20.0, 1.0));
        verify(netClient).send(any(StateData.class));
        clearInvocations(netClient);
        client.onStatusChange(state(PlaybackState.PAUSED, 10.0, 1.0));

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void sendsAndKeepAliveDoNotChangeServerExpectation() {
        PlayerState expected = state(PlaybackState.PAUSED, 10.0, 1.0);
        playerStatus.set(expected);
        recordServerState(PlaybackState.PAUSED, 10.0, 1.0);

        client.onStatusChange(state(PlaybackState.PLAYING, 20.0, 1.0));
        client.sendKeepAlive();
        clearInvocations(netClient);
        client.onStatusChange(expected.copy());

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void serverExpectationKeepsAnOwnedSnapshot() {
        PlayerState serverStatus = state(PlaybackState.PAUSED, 10.0, 1.0);
        when(videoPlayer.getStatus()).thenReturn(serverStatus);
        client.handleResponse(stateData(PlaybackState.PAUSED, 10.0, 1.0));

        serverStatus.setPlaybackState(PlaybackState.PLAYING);
        serverStatus.setPosition(99.0);
        clearInvocations(netClient);
        client.onStatusChange(state(PlaybackState.PAUSED, 10.0, 1.0));

        verify(netClient, never()).send(any(StateData.class));
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

    private void recordServerState(
            PlaybackState playbackState,
            double position,
            double playbackSpeed
    ) {
        client.handleResponse(stateData(
                playbackState,
                position,
                playbackSpeed
        ));
        clearInvocations(netClient);
    }

    private static StateData stateData(
            PlaybackState playbackState,
            double position,
            double playbackSpeed
    ) {
        State wireState = playbackState == PlaybackState.PAUSED
                ? State.PAUSED
                : State.PLAYING;
        return new StateData(
                Command.UPDATE_STATE,
                wireState,
                position,
                playbackSpeed
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
