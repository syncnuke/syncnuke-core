package io.github.syncnuke.player.internal;

import io.github.syncnuke.player.VideoPlayer;

import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerManagerTest {

    private static final class FakeTimingService implements TimingService {

        private long now;
        private final List<Scheduled> tasks = new ArrayList<>();

        @Override
        public long getCurrentTime() {
            return now;
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable task,
                long initialDelay,
                long delay,
                TimeUnit unit
        ) {
            Scheduled scheduled = new Scheduled(
                    task,
                    now + unit.toMillis(initialDelay),
                    unit.toMillis(delay)
            );
            tasks.add(scheduled);
            return scheduled;
        }

        @Override
        public void shutdown() {
            for (Scheduled task : new ArrayList<>(tasks)) {
                task.cancel(false);
            }
        }

        void advance(long millis) {
            long target = now + millis;
            while (true) {
                Scheduled next = tasks.stream()
                        .filter(task -> !task.cancelled && task.next <= target)
                        .min((left, right) -> Long.compare(left.next, right.next))
                        .orElse(null);
                if (next == null) {
                    break;
                }
                now = next.next;
                next.task.run();
                if (!next.cancelled) {
                    next.next += next.period;
                }
            }
            now = target;
        }

        int activeTaskCount() {
            return (int) tasks.stream()
                    .filter(task -> !task.cancelled)
                    .count();
        }

        private final class Scheduled implements ScheduledFuture<Object> {
            private final Runnable task;
            private final long period;
            private long next;
            private boolean cancelled;

            private Scheduled(Runnable task, long next, long period) {
                this.task = task;
                this.next = next;
                this.period = period;
            }

            @Override
            public long getDelay(TimeUnit unit) {
                return unit.convert(next - now, TimeUnit.MILLISECONDS);
            }

            @Override
            public int compareTo(Delayed other) {
                return Long.compare(
                        getDelay(TimeUnit.MILLISECONDS),
                        other.getDelay(TimeUnit.MILLISECONDS)
                );
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled = true;
                return true;
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public boolean isDone() {
                return cancelled;
            }

            @Override
            public Object get() {
                return null;
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                return null;
            }
        }
    }

    @Mock
    private VideoPlayer videoPlayer;
    @Mock
    private VideoPlayerEventListener listener;

    private FakeTimingService clock;
    private AtomicReference<PlayerState> rawStatus;
    private PlayerManager manager;

    @BeforeEach
    void setUp() throws Exception {
        clock = new FakeTimingService();
        rawStatus = new AtomicReference<>(state(PlaybackState.PAUSED, 0.0, 1.0));
        when(videoPlayer.getStatus())
                .thenAnswer(invocation -> {
                    PlayerState status = rawStatus.get().copy();
                    status.setLastUpdateTime(clock.getCurrentTime());
                    return status;
                });

        Constructor<PlayerManager> constructor = PlayerManager.class
                .getDeclaredConstructor(TimingService.class, long.class);
        constructor.setAccessible(true);
        manager = constructor.newInstance(clock, 50L);
        manager.setListener(listener);
        manager.start(videoPlayer);
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    @Test
    void newPlayerState_hasSafeDefaults() {
        PlayerState status = new PlayerState();

        assertEquals(PlaybackState.PAUSED, status.getPlaybackState());
        assertEquals(0.0, status.getPosition());
        assertEquals(1.0, status.getPlaybackSpeed());
    }

    @Test
    void start_capturesInitialStatusWithoutNotification() {
        PlayerState status = manager.getStatus();

        assertEquals(PlaybackState.PAUSED, status.getPlaybackState());
        assertEquals(0.0, status.getPosition());
        verify(videoPlayer, times(2)).getStatus();
        verify(listener, never()).onStatusChange(any());
    }

    @Test
    void polling_startsAtDefaultInterval() {
        clock.advance(49);
        verify(videoPlayer, times(1)).getStatus();

        clock.advance(1);
        verify(videoPlayer, times(2)).getStatus();
    }

    @Test
    void ordinaryPlayingProgress_refreshesWithoutNotification() {
        setRawStatus(PlaybackState.PLAYING, 0.0, 1.0);
        clock.advance(50);
        clearInvocations(listener);

        setRawStatus(PlaybackState.PLAYING, 0.05, 1.0);
        clock.advance(50);

        verify(listener, never()).onStatusChange(any());
        assertEquals(0.05, manager.getStatus().getPosition());
    }

    @Test
    void seekLikeDiscrepancy_notifiesWithFullStatus() {
        setRawStatus(PlaybackState.PLAYING, 0.0, 1.0);
        clock.advance(1000);
        clearInvocations(listener);

        setRawStatus(PlaybackState.PLAYING, 2.0, 1.0);
        clock.advance(1000);

        ArgumentCaptor<PlayerState> statusCaptor =
                ArgumentCaptor.forClass(PlayerState.class);
        verify(listener).onStatusChange(statusCaptor.capture());
        assertEquals(PlaybackState.PLAYING,
                statusCaptor.getValue().getPlaybackState());
        assertEquals(2.0, statusCaptor.getValue().getPosition());
    }

    @Test
    void playbackStateChange_notifies() {
        setRawStatus(PlaybackState.PLAYING, 0.0, 1.0);

        clock.advance(1000);

        verify(listener).onStatusChange(any());
    }

    @Test
    void playbackSpeedChange_notifiesEvenThoughItCannotBeCommanded() {
        setRawStatus(PlaybackState.PAUSED, 0.0, 1.5);

        clock.advance(1000);

        ArgumentCaptor<PlayerState> statusCaptor =
                ArgumentCaptor.forClass(PlayerState.class);
        verify(listener).onStatusChange(statusCaptor.capture());
        assertEquals(1.5, statusCaptor.getValue().getPlaybackSpeed());
    }

    @Test
    void pollingWithoutListener_stillRefreshesStatus() {
        manager.setListener(null);
        setRawStatus(PlaybackState.PAUSED, 12.0, 1.0);

        clock.advance(1000);

        assertEquals(12.0, manager.getStatus().getPosition());
    }

    @Test
    void getStatus_returnsDefensiveCopy() {
        PlayerState first = manager.getStatus();
        first.setPosition(99.0);

        PlayerState second = manager.getStatus();

        assertNotSame(first, second);
        assertEquals(0.0, second.getPosition());
    }

    @Test
    void getStatus_readsCurrentBackingPlayerStatusWithoutWaitingForPoll() {
        setRawStatus(PlaybackState.PLAYING, 12.0, 1.0);

        PlayerState status = manager.getStatus();

        assertEquals(PlaybackState.PLAYING, status.getPlaybackState());
        assertEquals(12.0, status.getPosition());
    }

    @Test
    void updateStatus_issuesOnlyRequiredAvailableCommands() throws Exception {
        setRawStatus(PlaybackState.PAUSED, 10.0, 1.0);
        clock.advance(1000);
        clearInvocations(videoPlayer, listener);

        PlayerState desired = state(PlaybackState.PLAYING, 20.0, 2.0);
        manager.updateStatus(desired);

        verify(videoPlayer).getStatus();
        verify(videoPlayer).play();
        verify(videoPlayer).setPlaybackSpeed(2.0);
        verify(videoPlayer).seek(20.0);
        verify(videoPlayer, never()).close();
        assertEquals(PlaybackState.PAUSED,
                manager.getStatus().getPlaybackState());
        assertEquals(10.0, manager.getStatus().getPosition());
    }

    @Test
    void updateStatus_canPauseWithoutClosingBackingPlayer() throws Exception {
        setRawStatus(PlaybackState.PLAYING, 0.0, 1.0);
        clock.advance(1000);
        clearInvocations(videoPlayer, listener);

        PlayerState desired = state(PlaybackState.PAUSED, 0.0, 1.0);
        manager.updateStatus(desired);

        verify(videoPlayer).pause();
        verify(videoPlayer, never()).play();
        verify(videoPlayer, never()).seek(org.mockito.ArgumentMatchers.anyDouble());
        verify(videoPlayer, never()).close();
    }

    @Test
    void updateStatus_observesBackingPlayerWhenPollingIsDisabled() throws Exception {
        manager.close();
        Constructor<PlayerManager> constructor = PlayerManager.class
                .getDeclaredConstructor(TimingService.class, long.class);
        constructor.setAccessible(true);
        manager = constructor.newInstance(clock, 0L);
        manager.start(videoPlayer);

        setRawStatus(PlaybackState.PLAYING, 0.0, 1.0);
        clearInvocations(videoPlayer);

        PlayerState desired = state(PlaybackState.PAUSED, 0.0, 1.0);
        manager.updateStatus(desired);

        verify(videoPlayer).getStatus();
        verify(videoPlayer).pause();
        verify(videoPlayer, never()).play();
    }

    @Test
    void updateStatus_skipsCommandsWhenAlreadyAligned() throws Exception {
        PlayerState desired = state(PlaybackState.PAUSED, 0.1, 1.0);
        clearInvocations(videoPlayer);

        manager.updateStatus(desired);

        verify(videoPlayer, never()).play();
        verify(videoPlayer, never()).pause();
        verify(videoPlayer, never()).setPlaybackSpeed(org.mockito.ArgumentMatchers.anyDouble());
        verify(videoPlayer, never()).seek(org.mockito.ArgumentMatchers.anyDouble());
        verify(videoPlayer, never()).close();
    }

    @Test
    void start_replacesPollingTaskAndClosesPreviousPlayer() throws Exception {
        VideoPlayer replacement = org.mockito.Mockito.mock(VideoPlayer.class);
        when(replacement.getStatus())
                .thenReturn(state(PlaybackState.PAUSED, 3.0, 1.0));

        manager.start(replacement);

        verify(videoPlayer).close();
        assertEquals(1, clock.activeTaskCount());
        assertEquals(3.0, manager.getStatus().getPosition());
    }

    @Test
    void failedPoll_doesNotStopFuturePolls() {
        when(videoPlayer.getStatus())
                .thenThrow(new IllegalStateException("critical failure"))
                .thenAnswer(invocation -> {
                    PlayerState status = rawStatus.get().copy();
                    status.setLastUpdateTime(clock.getCurrentTime());
                    return status;
                });

        assertDoesNotThrow(() -> clock.advance(50));
        setRawStatus(PlaybackState.PLAYING, 0.0, 1.0);
        clock.advance(50);

        verify(listener).onStatusChange(any());
    }

    @Test
    void failedListener_doesNotStopFuturePolls() {
        doThrow(new IllegalStateException("listener failure"))
                .doNothing()
                .when(listener)
                .onStatusChange(any());

        setRawStatus(PlaybackState.PLAYING, 0.0, 1.0);
        clock.advance(1000);
        setRawStatus(PlaybackState.PAUSED, 0.0, 1.0);
        clock.advance(1000);

        verify(listener, times(2)).onStatusChange(any());
        assertEquals(PlaybackState.PAUSED,
                manager.getStatus().getPlaybackState());
    }

    @Test
    void operationsBeforeStart_areRejected() throws Exception {
        manager.close();

        Constructor<PlayerManager> constructor = PlayerManager.class
                .getDeclaredConstructor(TimingService.class, long.class);
        constructor.setAccessible(true);
        PlayerManager unstarted = constructor.newInstance(
                new FakeTimingService(),
                50L
        );

        assertThrows(IllegalStateException.class, unstarted::getStatus);
        unstarted.close();
    }

    @Test
    void close_cancelsPollAndClosesBackingPlayer() throws Exception {
        manager.close();

        assertEquals(0, clock.activeTaskCount());
        verify(videoPlayer).close();
    }

    private void setRawStatus(
            PlaybackState playbackState,
            double position,
            double speed
    ) {
        rawStatus.set(state(playbackState, position, speed));
    }

    private static PlayerState state(
            PlaybackState playbackState,
            double position,
            double speed
    ) {
        PlayerState status = new PlayerState();
        status.setPlaybackState(playbackState);
        status.setPosition(position);
        status.setPlaybackSpeed(speed);
        return status;
    }
}
