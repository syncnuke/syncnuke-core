package io.github.syncnuke.client.internal.protocol.datasaver;

import io.github.syncnuke.client.RoomInfo;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.net.NetListener;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.syncnuke.client.ClientTestSupport.executor;
import static io.github.syncnuke.client.ClientTestSupport.awaitIdle;
import static io.github.syncnuke.client.ClientTestSupport.runEvent;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataSaverClientTest {

    @Mock
    private PlayerManager mediaPlayer;
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
    private NetListener<BaseData> listener;

    @BeforeEach
    void setUp() {
        playerStatus.set(state(PlaybackState.PAUSED, 0.0, 1.0));
        lenient().when(mediaPlayer.getStatus())
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
                mediaPlayer,
                netClient,
                timingService
        );

        awaitIdle(client);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<NetListener<BaseData>> listenerCaptor = ArgumentCaptor.forClass(NetListener.class);
        verify(netClient).addListener(listenerCaptor.capture());
        listener = listenerCaptor.getValue();
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
        awaitIdle(client);

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
        awaitIdle(client);
        clearInvocations(netClient);

        receive(new ConnectData("next.example", 27032));

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
        awaitIdle(client);
        clearInvocations(netClient);

        client.close();
        awaitIdle(client);

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
        awaitIdle(client);
        receive(new JoinData("second", "room", null));

        assertEquals("room", client.getRoomInfo().getRoom());
        assertEquals(
                Arrays.asList("first", "second"),
                client.getRoomInfo().getUsers()
        );

        receive(new LeaveData("second", "room", null));

        assertEquals(
                Collections.singletonList("first"),
                client.getRoomInfo().getUsers()
        );
    }

    @Test
    void localStateWaitsForRoomMembership() {
        client.login("user", "room", null);
        awaitIdle(client);

        client.onStatusChange(state(PlaybackState.PLAYING, 30.0, 1.0));
        awaitIdle(client);

        verify(netClient, never()).send(any(StateData.class));
        verify(mediaPlayer, never()).updateStatus(any(PlayerState.class));
    }

    @Test
    void occupiedRoomPausesAndSkipsLocalStateUntilServerStateArrives() {
        client.login("user", "room", null);
        awaitIdle(client);
        receive(new JoinData("existing", "room", null));
        playerStatus.set(state(PlaybackState.PLAYING, 30.0, 1.0));

        client.onStatusChange(playerStatus.get().copy());
        awaitIdle(client);

        ArgumentCaptor<PlayerState> target = ArgumentCaptor.forClass(PlayerState.class);
        verify(mediaPlayer).updateStatus(target.capture());
        assertEquals(PlaybackState.PAUSED, target.getValue().getPlaybackState());
        assertEquals(30.0, target.getValue().getPosition());
        verify(netClient, never()).send(any(StateData.class));

        clearInvocations(mediaPlayer);
        receive(stateData(PlaybackState.PLAYING, 120.0, 1.0));
        verify(mediaPlayer).updateStatus(target.capture());
        assertEquals(120.0, target.getValue().getPosition());
        verify(netClient, never()).send(any(StateData.class));

        client.onStatusChange(state(PlaybackState.PAUSED, 120.0, 1.0));
        awaitIdle(client);
        verify(netClient).send(any(StateData.class));
    }

    @Test
    void emptyRoomPublishesLocalProgressInsteadOfApplyingServerState() {
        client.login("user", "room", null);
        awaitIdle(client);
        playerStatus.set(state(PlaybackState.PLAYING, 30.0, 1.25));

        receive(stateData(PlaybackState.PAUSED, 0.0, 1.0));

        ArgumentCaptor<StateData> message = ArgumentCaptor.forClass(StateData.class);
        verify(netClient).send(message.capture());
        assertEquals(State.PLAYING, message.getValue().getState());
        assertEquals(30.0, message.getValue().getPosition());
        assertEquals(1.25, message.getValue().getPlaybackSpeed());
        verify(mediaPlayer, never()).updateStatus(any(PlayerState.class));

        clearInvocations(netClient);
        client.onStatusChange(state(PlaybackState.PAUSED, 30.0, 1.25));
        awaitIdle(client);
        verify(netClient).send(any(StateData.class));
    }

    @Test
    void redirectWaitsForNewRoomMembership() {
        client.login("user", "room", null);
        awaitIdle(client);
        receive(stateData(PlaybackState.PAUSED, 0.0, 1.0));
        receive(new ConnectData("next.example", 27032));
        clearInvocations(netClient);

        client.onStatusChange(state(PlaybackState.PLAYING, 30.0, 1.0));
        awaitIdle(client);

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void keepAliveOnlyPingsBeforeServerStateIsKnown() {
        client.login("user", "room", null);
        awaitIdle(client);
        clearInvocations(netClient);
        currentTime.addAndGet(10000);

        keepAliveTask.run();
        awaitIdle(client);

        verify(netClient).send(any(PingData.class));
        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void sendFailureDoesNotEscapeStatusCallback() throws Exception {
        recordServerState(PlaybackState.PLAYING, 0.0, 1.0);
        doThrow(new IllegalStateException("send failed"))
                .when(netClient).send(any(StateData.class));

        assertDoesNotThrow(() -> client.onStatusChange(
                state(PlaybackState.PAUSED, 0.0, 1.0)
        ));
        awaitIdle(client);
        assertTrue(executor(client, "eventQueue").isTerminated());
        verify(netClient).close();
    }

    @Test
    void playbackStateChangeIsSent() {
        recordServerState(PlaybackState.PAUSED, 0.0, 1.0);

        client.onStatusChange(state(PlaybackState.PLAYING, 0.0, 1.0));
        awaitIdle(client);

        verify(netClient).send(any(StateData.class));
    }

    @Test
    void progressChangeBelowThresholdIsNotSent() {
        recordServerState(PlaybackState.PLAYING, 10.0, 1.0);

        client.onStatusChange(state(PlaybackState.PLAYING, 10.3, 1.0));
        awaitIdle(client);

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void pausedSeekIsSent() {
        recordServerState(PlaybackState.PAUSED, 10.0, 1.0);

        client.onStatusChange(state(PlaybackState.PAUSED, 11.1, 1.0));
        awaitIdle(client);

        verify(netClient).send(any(StateData.class));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1})
    void progressChangeWithInvalidPlaybackSpeedIsSent(double speed) {
        recordServerState(PlaybackState.PLAYING, 0.0, 1.0);
        currentTime.incrementAndGet();

        client.onStatusChange(state(PlaybackState.PLAYING, 0.6, speed));
        awaitIdle(client);

        verify(netClient).send(any(StateData.class));
    }

    @Test
    void seekThatDiffersFromExpectedProgressionIsSent() {
        recordServerState(PlaybackState.PLAYING, 0.0, 1.0);
        currentTime.addAndGet(600);

        client.onStatusChange(state(PlaybackState.PLAYING, 2.0, 1.0));
        awaitIdle(client);

        verify(netClient).send(any(StateData.class));
    }

    @Test
    void backwardSeekCannotMasqueradeAsForwardProgression() {
        recordServerState(PlaybackState.PLAYING, 30.0, 1.0);
        currentTime.addAndGet(10000);

        client.onStatusChange(state(PlaybackState.PLAYING, 20.0, 1.0));
        awaitIdle(client);

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
        awaitIdle(client);

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void ordinaryProgressionIsNotSent() {
        recordServerState(PlaybackState.PLAYING, 0.0, 1.0);
        currentTime.addAndGet(600);

        client.onStatusChange(state(PlaybackState.PLAYING, 0.6, 1.0));
        awaitIdle(client);

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
        }).when(mediaPlayer).updateStatus(any(PlayerState.class));

        receive(stateData(PlaybackState.PAUSED, 10.0, 1.0));

        verify(mediaPlayer).updateStatus(any(PlayerState.class));
    }

    @Test
    void localSendUpdatesServerExpectation() {
        recordServerState(PlaybackState.PAUSED, 10.0, 1.0);

        client.onStatusChange(state(PlaybackState.PLAYING, 20.0, 1.0));
        awaitIdle(client);
        verify(netClient).send(any(StateData.class));
        clearInvocations(netClient);
        client.onStatusChange(state(PlaybackState.PLAYING, 20.0, 1.0));
        awaitIdle(client);

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void sendsAndKeepAliveUpdateServerExpectation() {
        PlayerState sent = state(PlaybackState.PLAYING, 20.0, 1.0);
        playerStatus.set(sent);
        recordServerState(PlaybackState.PAUSED, 10.0, 1.0);

        client.onStatusChange(sent);
        awaitIdle(client);
        runEvent(client, client::sendKeepAlive);
        awaitIdle(client);
        clearInvocations(netClient);
        client.onStatusChange(sent.copy());
        awaitIdle(client);

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void serverExpectationKeepsAnOwnedSnapshot() {
        PlayerState serverStatus = state(PlaybackState.PAUSED, 10.0, 1.0);
        when(mediaPlayer.getStatus()).thenReturn(serverStatus);
        receive(stateData(PlaybackState.PAUSED, 10.0, 1.0));

        serverStatus.setPlaybackState(PlaybackState.PLAYING);
        serverStatus.setPosition(99.0);
        clearInvocations(netClient);
        client.onStatusChange(state(PlaybackState.PAUSED, 10.0, 1.0));
        awaitIdle(client);

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void inboundStateIsHandledWhileLocalSendIsInProgress() {
        recordServerState(PlaybackState.PAUSED, 0.0, 1.0);
        clearInvocations(mediaPlayer);
        StateData inbound = new StateData(
                Command.UPDATE_STATE,
                State.PLAYING,
                15.0,
                1.25
        );
        doAnswer(invocation -> {
            listener.onResponse(inbound);
            return null;
        }).when(netClient).send(any(StateData.class));

        client.onStatusChange(
                state(PlaybackState.PLAYING, 5.0, 1.0)
        );
        awaitIdle(client);

        ArgumentCaptor<PlayerState> targetCaptor =
                ArgumentCaptor.forClass(PlayerState.class);
        verify(mediaPlayer).updateStatus(targetCaptor.capture());
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

        receive(new StateData(
                Command.UPDATE_STATE,
                State.PLAYING,
                9.0,
                2.0
        ));

        ArgumentCaptor<PlayerState> targetCaptor =
                ArgumentCaptor.forClass(PlayerState.class);
        verify(mediaPlayer).updateStatus(targetCaptor.capture());
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
        receive(new StateData(
                Command.UPDATE_STATE,
                State.PAUSED,
                10.0,
                1.0
        ));
        clearInvocations(netClient);

        client.onStatusChange(expected.copy());
        awaitIdle(client);

        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void keepAliveSendsUnchangedStatusAtExactInterval() {
        PlayerState expected = state(PlaybackState.PAUSED, 10.0, 1.0);
        playerStatus.set(expected);
        receive(new StateData(
                Command.UPDATE_STATE,
                State.PAUSED,
                10.0,
                1.0
        ));
        runEvent(client, client::sendKeepAlive);
        awaitIdle(client);
        clearInvocations(netClient);

        currentTime.addAndGet(10000);
        keepAliveTask.run();
        awaitIdle(client);

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

        DataSaverClient zeroIntervalClient = new DataSaverClient(
                "localhost",
                8080,
                0,
                mediaPlayer,
                zeroIntervalNetClient,
                timingService
        );

        awaitIdle(zeroIntervalClient);
        zeroIntervalClient.close();
        awaitIdle(zeroIntervalClient);
        verify(timingService, never()).schedule(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class)
        );
    }

    @Test
    void blockedSendDoesNotDelayInboundStateOrKeepAliveAndCannotOverwriteExpectation()
            throws Exception {
        recordServerState(PlaybackState.PAUSED, 0.0, 1.0);
        clearInvocations(mediaPlayer);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return null;
        }).when(netClient).send(any(StateData.class));

        try {
            client.onStatusChange(state(PlaybackState.PLAYING, 5.0, 1.0));
            assertTrue(sending.await(1, TimeUnit.SECONDS));
            listener.onResponse(stateData(PlaybackState.PAUSED, 15.0, 1.25));
            client.onStatusChange(state(PlaybackState.PAUSED, 15.0, 1.25));
            executor(client, "eventQueue").submit(() -> {}).get(1, TimeUnit.SECONDS);
            executor(client, "playerExecutor").submit(() -> {}).get(1, TimeUnit.SECONDS);
            verify(mediaPlayer).updateStatus(any(PlayerState.class));

            currentTime.addAndGet(10000);
            keepAliveTask.run();
            executor(client, "eventQueue").submit(() -> {}).get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        awaitIdle(client);

        ArgumentCaptor<StateData> messages = ArgumentCaptor.forClass(StateData.class);
        verify(netClient, times(2)).send(messages.capture());
        assertEquals(5.0, messages.getAllValues().get(0).getPosition());
        assertEquals(State.PAUSED, messages.getAllValues().get(1).getState());
        assertEquals(15.0, messages.getAllValues().get(1).getPosition());
        clearInvocations(netClient);
        client.onStatusChange(state(PlaybackState.PAUSED, 15.0, 1.25));
        awaitIdle(client);
        verify(netClient, never()).send(any(StateData.class));
    }

    @Test
    void blockedPlayerCommandDoesNotDelayMembershipEventsAndCommandsStayOrdered()
            throws Exception {
        CountDownLatch applying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            applying.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return null;
        }).when(mediaPlayer).updateStatus(any(PlayerState.class));

        try {
            listener.onResponse(stateData(PlaybackState.PLAYING, 15.0, 1.0));
            assertTrue(applying.await(1, TimeUnit.SECONDS));
            client.login("user", "room", null);
            executor(client, "eventQueue").submit(() -> {}).get(1, TimeUnit.SECONDS);
            assertEquals("room", client.getRoomInfo().getRoom());
            verify(netClient, timeout(1000)).send(any(JoinData.class));
            verify(mediaPlayer).getStatus();
        } finally {
            release.countDown();
        }
        awaitIdle(client);
        // A subsequent server state reads the player after the earlier command.
        receive(new JoinData("existing", "room", null));
        receive(stateData(PlaybackState.PAUSED, 20.0, 1.0));
        ArgumentCaptor<PlayerState> targets = ArgumentCaptor.forClass(PlayerState.class);
        verify(mediaPlayer, times(2)).updateStatus(targets.capture());
        assertEquals(15.0, targets.getAllValues().get(0).getPosition());
        assertEquals(20.0, targets.getAllValues().get(1).getPosition());
    }

    @Test
    void intermediatePlayerFeedbackCannotEchoOverNewerServerState() throws Exception {
        CountDownLatch applying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            applying.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            client.onStatusChange(((PlayerState) invocation.getArgument(0)).copy());
            return null;
        }).when(mediaPlayer).updateStatus(any(PlayerState.class));
        try {
            listener.onResponse(stateData(PlaybackState.PLAYING, 15.0, 1.0));
            assertTrue(applying.await(1, TimeUnit.SECONDS));
            listener.onResponse(stateData(PlaybackState.PAUSED, 20.0, 1.0));
        } finally {
            release.countDown();
        }
        awaitIdle(client);
        verify(netClient, never()).send(any(StateData.class));
        client.onStatusChange(state(PlaybackState.PLAYING, 25.0, 1.0));
        awaitIdle(client);
        verify(netClient).send(any(StateData.class));
    }

    @Test
    void userChangeDuringPendingPlayerCommandIsStillSent() throws Exception {
        CountDownLatch applying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            applying.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return null;
        }).when(mediaPlayer).updateStatus(any(PlayerState.class));
        try {
            listener.onResponse(stateData(PlaybackState.PLAYING, 15.0, 1.0));
            assertTrue(applying.await(1, TimeUnit.SECONDS));
            client.onStatusChange(state(PlaybackState.PAUSED, 30.0, 1.0));
            executor(client, "eventQueue").submit(() -> {}).get(1, TimeUnit.SECONDS);
            executor(client, "networkExecutor").submit(() -> {}).get(1, TimeUnit.SECONDS);
            ArgumentCaptor<StateData> message = ArgumentCaptor.forClass(StateData.class);
            verify(netClient).send(message.capture());
            assertEquals(State.PAUSED, message.getValue().getState());
            assertEquals(30.0, message.getValue().getPosition());
        } finally {
            release.countDown();
        }
        awaitIdle(client);
    }

    @Test
    void blockedLiveReadTimesOutAndLetsQueuedEventsContinue() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        doAnswer(invocation -> {
            reading.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return new PlayerState();
        }).when(mediaPlayer).getStatus();

        listener.onResponse(stateData(PlaybackState.PLAYING, 15.0, 1.0));
        assertTrue(reading.await(1, TimeUnit.SECONDS));
        client.login("user", "room", null);

        verify(netClient, timeout(2000)).send(any(JoinData.class));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        awaitIdle(client);
        verify(mediaPlayer, never()).updateStatus(any());
    }

    @Test
    void roomGetterBuildsMutableCopyFromCurrentQueuedMembership() {
        client.login("first", "room", null);
        listener.onResponse(new JoinData("second", "room", null));

        RoomInfo info = client.getRoomInfo();
        assertEquals("room", info.getRoom());
        assertEquals(List.of("first", "second"), info.getUsers());
        info.getUsers().add("local edit");
        assertEquals(List.of("first", "second"), client.getRoomInfo().getUsers());

        listener.onResponse(new LeaveData("second", "room", null));
        assertEquals(List.of("first"), client.getRoomInfo().getUsers());
    }

    @Test
    void roomGetterCanReadFromEventQueueWithoutDeadlock() throws Exception {
        client.login("first", "room", null);
        CompletableFuture<RoomInfo> result = new CompletableFuture<>();
        runEvent(client, () -> result.complete(client.getRoomInfo()));

        assertEquals(List.of("first"), result.get(1, TimeUnit.SECONDS).getUsers());
    }

    @Test
    void roomGetterRejectsReadAfterQueueShutdown() {
        client.close();
        awaitIdle(client);

        assertThrows(RejectedExecutionException.class, client::getRoomInfo);
    }

    @Test
    void playerEventOwnsItsSnapshotBeforeItIsQueued() throws Exception {
        recordServerState(PlaybackState.PAUSED, 0.0, 1.0);
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runEvent(client, () -> {
            busy.countDown();
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        try {
            assertTrue(busy.await(1, TimeUnit.SECONDS));
            PlayerState status = state(PlaybackState.PLAYING, 5.0, 1.25);
            client.onStatusChange(status);
            status.setPosition(99.0);
            status.setPlaybackState(PlaybackState.PAUSED);
        } finally {
            release.countDown();
        }
        awaitIdle(client);
        ArgumentCaptor<StateData> message = ArgumentCaptor.forClass(StateData.class);
        verify(netClient).send(message.capture());
        assertEquals(State.PLAYING, message.getValue().getState());
        assertEquals(5.0, message.getValue().getPosition());
    }

    @Test
    void closeIsOrderedIdempotentAndIgnoresLateCallbacks() throws Exception {
        clearInvocations(netClient);
        client.login("user", "room", "password");
        client.close();
        client.close();
        client.onStatusChange(state(PlaybackState.PLAYING, 30.0, 1.0));
        listener.onResponse(stateData(PlaybackState.PLAYING, 50.0, 1.0));
        keepAliveTask.run();
        awaitIdle(client);

        InOrder order = inOrder(netClient);
        order.verify(netClient).send(any(JoinData.class));
        order.verify(netClient).send(any(LeaveData.class));
        order.verify(netClient).close();
        order.verifyNoMoreInteractions();
        verify(keepAliveFuture).cancel(false);
        verify(timingService).shutdown();
        verify(mediaPlayer, never()).updateStatus(any());
        assertTrue(executor(client, "eventQueue").isTerminated());
    }

    @Test
    void blockedSendIsInterruptedDuringCloseWithoutHoldingUpQueueShutdown()
            throws Exception {
        recordServerState(PlaybackState.PAUSED, 0.0, 1.0);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(netClient).send(any(StateData.class));
        client.onStatusChange(state(PlaybackState.PLAYING, 5.0, 1.0));
        assertTrue(sending.await(1, TimeUnit.SECONDS));
        client.close();
        assertTrue(executor(client, "eventQueue").awaitTermination(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        verify(netClient, timeout(1000).atLeastOnce()).close();
    }

    @AfterEach
    void tearDown() {
        client.close();
        awaitIdle(client);
    }

    private void receive(BaseData data) {
        listener.onResponse(data);
        awaitIdle(client);
    }

    private void recordServerState(
            PlaybackState playbackState,
            double position,
            double playbackSpeed
    ) {
        receive(stateData(
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
