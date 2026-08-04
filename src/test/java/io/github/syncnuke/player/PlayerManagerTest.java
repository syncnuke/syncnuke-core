package io.github.syncnuke.player;

import io.github.syncnuke.service.TimingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlayerManagerTest {

    /* ---------------------------------- test fakes ---------------------------------- */

    /** Simple deterministic clock / scheduler for unit tests. */
    private static final class FakeTimingService implements TimingService {

        private long now;                                // “current” time in ms
        private final List<Scheduled> tasks = new ArrayList<>();

        @Override public long getCurrentTime() { return now; }



        @Override
        public ScheduledFuture<?> schedule(Runnable task, long initialDelay, long delay, TimeUnit unit) {
            tasks.add(new Scheduled(task, now + unit.toMillis(initialDelay), unit.toMillis(delay)));
            return new ScheduledFuture<Runnable>() {
                @Override public int compareTo(Delayed delayed) { return 0; }
                @Override public boolean cancel(boolean mayInterruptIfRunning) { return tasks.removeIf(s -> s.r == task); }
                @Override public boolean isCancelled() { return tasks.stream().anyMatch(s -> s.r == task); }
                @Override public boolean isDone() { return false; } // always pending
                @Override public Runnable get() { return task; } // always returns the original task
                @Override public Runnable get(long l, TimeUnit timeUnit){ return null; }
                @Override public long getDelay(TimeUnit unit) { return unit.convert(tasks.stream().filter(s -> s.r == task).findFirst().orElseThrow().next - now, TimeUnit.MILLISECONDS); }
            };
        }

        @Override public void shutdown() { tasks.clear(); }

        /* ---------- helpers ---------- */

        /** Advance fake time and run any tasks whose next-run time is <= new time. */
        void advance(long millis) {
            long target = now + millis;
            while (true) {
                Scheduled next = tasks.stream()
                        .filter(s -> s.next <= target)
                        .min((a, b) -> Long.compare(a.next, b.next))
                        .orElse(null);
                if (next == null) break;          // nothing ready
                now = next.next;
                next.r.run();
                next.next += next.period;         // reschedule
            }
            now = target;
        }

        private static final class Scheduled {
            final Runnable r;
            final long period;
            long next;
            Scheduled(Runnable r,long initial,long period){this.r=r;this.period=period;this.next=initial;}
        }
    }

    /* ---------------------------------- fixtures ---------------------------------- */

    @Mock private VideoPlayer videoPlayer;
    @Mock private VideoPlayerEventListener listener;

    private FakeTimingService clock;
    private PlayerManager manager;

    @BeforeEach
    void setUp() throws Exception {
        // 1) fresh clock
        clock = new FakeTimingService();

        // 2) fresh singleton
        Field inst = PlayerManager.class.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, null);

        // 3) build manager with private ctor(PlayerManager(TimingService))
        Constructor<PlayerManager> ctor =
                PlayerManager.class.getDeclaredConstructor(TimingService.class);
        ctor.setAccessible(true);
        manager = ctor.newInstance(clock);               // inject fake clock

        // 4) basic stubbing
        when(videoPlayer.getPlaybackSpeed()).thenReturn(1.0);
        when(videoPlayer.getPosition()).thenReturn(0.0);
        when(videoPlayer.isPaused()).thenReturn(true);

        // 5) wire-up
        manager.setEventListener(listener);
        manager.start(videoPlayer);
    }

    @AfterEach
    void tearDown() throws Exception { manager.close(); }

    /* ---------------------------------- tests ---------------------------------- */

    @Test
    void play_delegates_and_updatesState() {
        manager.play();

        verify(videoPlayer).play();
        assertFalse(manager.isPaused());
    }

    @Test
    void pause_delegates_and_updatesState() {
        manager.pause();

        verify(videoPlayer).pause();
        assertTrue(manager.isPaused());
    }

    @Test
    void seek_delegates_and_updatesCache() {
        double pos = 12.34;
        manager.seek(pos);

        verify(videoPlayer).seek(pos);
        assertEquals(pos, manager.getPosition(), 1e-9);
    }

    @Test
    void scheduler_fires_only_after_cooldown() {
        // initial state: paused
        when(videoPlayer.isPaused()).thenReturn(false);   // emulate “now playing”

        clock.advance(29);   // < UPDATE_COOLDOWN, should NOT call listener
        verify(listener, never()).onPlay();

        clock.advance(1);    // at 30 ms boundary – should fire
        verify(listener).onPlay();
    }
    /* ------------------------------------------------------------------ *
     *  NEW tests that cover isSignificantProgressChange()                *
     * ------------------------------------------------------------------ */

    /**
     * Branch: timeDiff == 0  → expectedAdvance == 0 → method must return false
     * (no onSeek forwarded).
     */
    @Test
    void onSeek_whenNoTimeHasPassed_isIgnored() {
        // 0 ms have elapsed since PlayerManager was started → expectedAdvance = 0.
        double pos = 0.50;                       // 500 ms jump
        when(videoPlayer.getPosition()).thenReturn(pos);

        reset(listener);                         // drop any calls made by the scheduler
        manager.onSeek(pos);

        verify(listener, never()).onSeek(anyDouble());
    }

    /**
     * Branch: positionDiff ≥ UPDATE_COOLDOWN but
     *         relativeError ≤ DRIFT_THRESHOLD  → NOT significant.
     *
     * Scenario: 100 ms have passed, player advanced exactly 100 ms.
     */
    @Test
    void onSeek_whenProgressMatchesTime_isNotSignificant() {
        clock.advance(100);                      // fake 100 ms of playback

        double pos = 0.10;                       // 100 ms == expected advance
        when(videoPlayer.getPosition()).thenReturn(pos);

        reset(listener);
        manager.onSeek(pos);

        verify(listener, never()).onSeek(anyDouble());   // suppressed
    }

    /**
     * Branch: positionDiff ≥ UPDATE_COOLDOWN AND
     *         relativeError > DRIFT_THRESHOLD  → significant → listener called.
     *
     * Scenario: 100 ms elapsed but position jumped 500 ms.
     */
    @Test
    void onSeek_whenJumpLargeEnough_isForwarded() {
        clock.advance(100);                      // 100 ms out-of-date cache

        double pos = 0.50;                       // 500 ms jump → large relative error
        when(videoPlayer.getPosition()).thenReturn(pos);

        reset(listener);
        manager.onSeek(pos);

        verify(listener).onSeek(pos);            // forwarded
    }
}
