package io.github.syncnuke.client.internal.protocol.syncplay;

import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.syncnuke.client.data.RoomInfo;
import io.github.syncnuke.client.SyncClient;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.protocol.syncplay.command.*;
import io.github.syncnuke.client.internal.protocol.syncplay.data.SyncplayCodec;
import io.github.syncnuke.client.internal.protocol.syncplay.net.SyncplayTransport;
import io.github.syncnuke.player.NoMediaLoadedException;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.client.data.MediaInfo;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.player.data.PlayerStateUtils;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.service.TimingService;
import io.github.syncnuke.service.TimingServiceImpl;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import pl.syncplay.proto.SyncplayProto.Ping;
import pl.syncplay.proto.SyncplayProto.SetCommand;
import pl.syncplay.proto.SyncplayProto.StateMessage;
import pl.syncplay.proto.SyncplayProto.SyncplayMessage;

import java.net.ProtocolException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

@Slf4j
public final class SyncplayClient extends SyncClient<SyncplayMessage> {

    public static final String PROTOCOL = "syncplay";
    public static final String VERSION = "1.7.7";
    private static final int KEEP_ALIVE_INTERVAL_MILLIS = 1000;
    private static final double SEEK_THRESHOLD_SECONDS = 1.0;
    private static final double SLOWDOWN_RESET_SECONDS = 0.1;
    private static final double SLOWDOWN_RATE = 0.95;

    private final NetClient<SyncplayMessage> netClient;
    private final SyncplaySession session;
    private final Thresholds thresholds;
    private final HelloCommand hello;
    private final RoomCommand rooms;
    private final StateCommand state;
    private final ErrorCommand error;

    // Last commanded or published state, used to filter protocol feedback. The expected server state.
    private PlayerState expectedState;
    // Last state reported by the player, used to ensure requested corrections are applied.
    private PlayerState observedState;
    // State requested by us for the player to match, which has not been confirmed.
    private PlayerState requestedState;
    private long correctionExpires;
    private boolean retryPlayerUpdate;
    private boolean roomStateReceived;
    private long behindSince = -1;
    private boolean playbackSpeedReduced;
    private boolean mediaAnnounced;
    private MediaInfo announcedMedia;

    public SyncplayClient(String host, int port, PlayerManager mediaPlayer) {
        this(host, port, mediaPlayer, Thresholds.DEFAULT);
    }

    public SyncplayClient(String host, int port, PlayerManager mediaPlayer, Thresholds thresholds) {
        this(host, port, mediaPlayer, new SyncplayTransport(), new TimingServiceImpl(), thresholds);
    }

    SyncplayClient(String host, int port, PlayerManager mediaPlayer,
            NetClient<SyncplayMessage> netClient, TimingService timingService) {
        this(host, port, mediaPlayer, netClient, timingService, Thresholds.DEFAULT);
    }

    SyncplayClient(String host, int port, PlayerManager mediaPlayer,
            NetClient<SyncplayMessage> netClient, TimingService timingService, Thresholds thresholds) {
        super(KEEP_ALIVE_INTERVAL_MILLIS, mediaPlayer, timingService);
        this.netClient = Objects.requireNonNull(netClient, "netClient");
        session = new SyncplaySession();
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        state = new StateCommand(session, timingService, this::send);
        rooms = new RoomCommand(session, this::resetPlayback, this::send);
        hello = new HelloCommand(session, rooms, this::send);
        error = new ErrorCommand();
        configureTransport();
        connect(host, port, new SyncplayCodec());
    }

    private void configureTransport() {
        if (netClient instanceof SyncplayTransport transport) {
            session.setConnectionReady(false);
            transport.callbacks(() -> executeEvent(this::onConnected),
                    () -> executeEvent(this::onDisconnected), this::close);
        }
    }

    private void onConnected() {
        session.setConnectionReady(true);
        session.setLoggedIn(false);
        rooms.resetRoomState();
        if (session.getRequestedUsername() != null) {
            hello.send();
        }
    }

    private void onDisconnected() {
        session.setConnectionReady(false);
        session.setLoggedIn(false);
        rooms.resetRoomState();
    }

    @Override
    protected NetClient<SyncplayMessage> getNetClient() {
        return netClient;
    }

    @Override
    public void login(String username, String room, String password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(room, "room");
        executeEvent(() -> {
            session.setRoom(room);
            rooms.resetRoomState();
            if (session.isLoggedIn()) {
                rooms.changeRoom();
            } else {
                hello.login(username, password);
            }
        });
    }

    @Override
    public RoomInfo getRoomInfo() {
        return readState(session::getRoomInfo);
    }

    @Override
    protected void handleResponse(SyncplayMessage message) {
        try {
            dispatch(message);
        } catch (ProtocolException failure) {
            log.warn("{}", failure.getMessage());
            close();
        }
    }

    private void dispatch(SyncplayMessage message) throws ProtocolException {
        for (SyncplayMessage command : message.getCommandsList()) {
            dispatch(command);
        }
        switch (message.getBodyCase()) {
            case HELLO -> hello.handle(message.getHello());
            case SET -> rooms.handleSettings(message.getSet());
            case LIST -> rooms.handleList(message.getList());
            case STATE -> synchronizePlayback(message.getState());
            case ERROR -> error.handle();
            default -> {
                // Unsupported features have no player side effects.
            }
        }
    }

    private void synchronizePlayback(StateMessage message) throws ProtocolException {
        if (!session.isLoggedIn()) {
            return;
        }
        long now = state.handle(message);
        PlayerState local = readPlayer();
        announceMedia(local);
        PlayerState global = state.readServerState(message, now);
        if (global != null) {
            roomStateReceived = true;
            if (local != null) {
                local = applyServerState(global, local, message);
            }
        }
        state.reply(roomStateReceived && !retryPlayerUpdate ? local : null, message.getPing());
    }

    private PlayerState applyServerState(PlayerState global, PlayerState local, StateMessage message) {
        PlayerState targetState = createTargetState(global, local, message);
        if (needsPlayerUpdate(targetState, local, global)) {
            requestPlayerUpdate(targetState, local);
        } else if (expectedState == null) {
            updateServerState(targetState);
            observedState = new PlayerState(targetState);
        }
        return targetState;
    }

    private PlayerState createTargetState(PlayerState global, PlayerState local, StateMessage message) {
        PlayerState targetState = new PlayerState(local);
        boolean changedByAnotherUser = !session.getUsername().equals(message.getPlaystate().getSetBy());
        boolean doSeek = message.getPlaystate().getDoSeek();
        double ahead = local.getPosition() - global.getPosition();
        boolean pauseChanged = expectedState == null || retryPlayerUpdate
                || expectedState.getPlaybackState() != global.getPlaybackState();

        if (shouldUpdatePosition(global, local, message)) {
            targetState.setPosition(global.getPosition());
        }
        // An unchanged heartbeat must not overwrite a user action awaiting PlayerManager's poll.
        if (pauseChanged) {
            targetState.setPlaybackState(global.getPlaybackState());
        } else if (doSeek && changedByAnotherUser) {
            targetState.setPlaybackState(global.getPlaybackState());
        }

        if (session.isFollower() && !doSeek && ahead < -1.75) {
            updateFollowerPosition(targetState, global, ahead, changedByAnotherUser);
        } else {
            behindSince = -1;
        }
        if (!doSeek && global.getPlaybackState() == PlaybackState.PLAYING) {
            updatePlaybackSpeed(targetState, ahead, changedByAnotherUser);
        }
        targetState.setLastUpdateTime(getCurrentTime());
        return targetState;
    }

    private void requestPlayerUpdate(PlayerState targetState, PlayerState local) {
        if (Math.abs(targetState.getPosition() - local.getPosition()) > SEEK_THRESHOLD_SECONDS
                || targetState.getPlaybackState() != local.getPlaybackState()) {
            requestedState = new PlayerState(targetState);
            observedState = new PlayerState(local);
            correctionExpires = getCurrentTime() + 1000;
        }
        if (requestedState == null) {
            observedState = new PlayerState(targetState);
        }
        updateServerState(targetState);
        PlayerState commanded = expectedState;
        updatePlayerStatus(targetState, () -> handlePlayerUpdateFailure(commanded, local));
    }

    private void handlePlayerUpdateFailure(PlayerState commanded, PlayerState local) {
        if (expectedState != commanded) {
            return;
        }
        // Retry against the next authoritative state; preserve newer local actions.
        observedState = new PlayerState(commanded);
        requestedState = null;
        retryPlayerUpdate = true;
        playbackSpeedReduced = local.getPlaybackSpeed() == SLOWDOWN_RATE;
    }

    private void announceMedia(PlayerState local) {
        MediaInfo media = local == null ? null : new MediaInfo(local.getFileName(),
                Double.isFinite(local.getDuration()) ? Math.max(0, local.getDuration()) : 0);
        if (mediaAnnounced && Objects.equals(announcedMedia, media)) {
            return;
        }
        Value.Builder file = Value.newBuilder();
        if (local == null) {
            file.setNullValue(NullValue.NULL_VALUE);
        } else {
            // Only the basename is shared. Unknown names retain Syncplay's fallback convention.
            String fileName = media.getFileName();
            if (fileName == null || fileName.isEmpty()) {
                fileName = "**Hidden filename**";
            }
            Value name = Value.newBuilder().setStringValue(fileName).build();
            Value duration = Value.newBuilder().setNumberValue(media.getDuration()).build();
            Value size = Value.newBuilder().setNumberValue(0).build();
            file.setStructValue(Struct.newBuilder()
                    .putFields("name", name)
                    .putFields("duration", duration)
                    .putFields("size", size));
        }
        send(SyncplayMessage.newBuilder().setSet(SetCommand.newBuilder().setExtensions(
                Struct.newBuilder().putFields("file", file.build()))).build());
        mediaAnnounced = true;
        announcedMedia = media;
        session.setMedia(media);
        if (local == null) {
            expectedState = null;
            observedState = null;
            requestedState = null;
            behindSince = -1;
            playbackSpeedReduced = false;
        }
    }

    private boolean shouldUpdatePosition(PlayerState global, PlayerState local, StateMessage message) {
        if (expectedState == null || retryPlayerUpdate) {
            return true;
        }
        // Do not reapply a position change originating from this client.
        if (session.getUsername().equals(message.getPlaystate().getSetBy())) {
            return false;
        }
        if (message.getPlaystate().getDoSeek()) {
            return true;
        }
        if (global.getPlaybackState() == PlaybackState.PAUSED) {
            // A paused heartbeat cannot distinguish drift from a seek awaiting the next poll.
            return expectedState.getPlaybackState() != PlaybackState.PAUSED;
        }
        return local.getPosition() - global.getPosition() > thresholds.rewind();
    }

    private boolean needsPlayerUpdate(PlayerState targetState, PlayerState local, PlayerState global) {
        if (targetState.getPlaybackState() != local.getPlaybackState()) {
            return true;
        }
        if (targetState.getPosition() != local.getPosition()) {
            return true;
        }
        if (targetState.getPlaybackSpeed() != local.getPlaybackSpeed()) {
            return true;
        }
        if (expectedState == null) {
            return false;
        }
        // A local action may already match the new server state before its callback arrives.
        return expectedState.getPlaybackState() != global.getPlaybackState();
    }

    private void updateFollowerPosition(PlayerState desired, PlayerState global, double ahead, boolean changedByAnotherUser) {
        // Ordinary rooms follow the slowest participant; only managed followers force catch-up.
        long now = getCurrentTime();
        if (behindSince < 0) {
            behindSince = now;
        } else if (now - behindSince > (thresholds.fastForward() - 1.75) * 1000
                && ahead < -thresholds.fastForward()) {
            if (changedByAnotherUser) {
                desired.setPosition(global.getPosition() + 0.25);
            }
            behindSince = now + 3000;
        }
    }

    private void updatePlaybackSpeed(PlayerState desired, double ahead, boolean changedByAnotherUser) {
        if (changedByAnotherUser && !playbackSpeedReduced && ahead > thresholds.slowdown()) {
            desired.setPlaybackSpeed(SLOWDOWN_RATE);
            playbackSpeedReduced = true;
        } else if (playbackSpeedReduced && ahead < SLOWDOWN_RESET_SECONDS) {
            desired.setPlaybackSpeed(1.0);
            playbackSpeedReduced = false;
        }
    }

    private void publishLocalChange(PlayerState snapshot) {
        snapshot.setLastUpdateTime(getCurrentTime());
        if (session.isLoggedIn()) {
            announceMedia(snapshot);
        }
        normalizePlaybackSpeed(snapshot);
        if (!session.isLoggedIn() || expectedState == null) {
            observedState = new PlayerState(snapshot);
            return;
        }
        if (handleRequestedState(snapshot)) {
            return;
        }

        boolean commandFeedback = hasPendingPlayerUpdate(target -> matches(snapshot, target))
                || matches(snapshot, expectedState);
        boolean changed = !commandFeedback && isSignificantChange(snapshot, expectedState);
        boolean seek = changed && isSignificantPositionChange(snapshot, expectedState);
        if (changed || requestedState == null) {
            observedState = new PlayerState(snapshot);
        }
        if (changed) {
            requestedState = null;
            updateServerState(snapshot);
            state.sendChange(snapshot, seek);
        }
    }

    private void normalizePlaybackSpeed(PlayerState snapshot) {
        if (snapshot.getPlaybackSpeed() == 1.0) {
            return;
        }
        if (playbackSpeedReduced && snapshot.getPlaybackSpeed() == SLOWDOWN_RATE) {
            return;
        }
        snapshot.setPlaybackSpeed(1.0);
        playbackSpeedReduced = false;
        updatePlayerStatus(snapshot);
    }

    /** Handles player feedback; returns true when no local change should be published. */
    private boolean handleRequestedState(PlayerState snapshot) {
        if (requestedState == null) {
            return false;
        }
        // IPC completion is not confirmation that the player has applied the command.
        readPlayer();
        if (requestedState == null) {
            return false;
        }
        if (getCurrentTime() > correctionExpires) {
            finishPlayerRequest(requestedState);
            return false;
        }
        if (matches(snapshot, requestedState)) {
            finishPlayerRequest(snapshot);
            return true;
        }
        // Keep pre-command observations from undoing an accepted request.
        return positionDifference(snapshot, observedState) <= SEEK_THRESHOLD_SECONDS;
    }

    private void finishPlayerRequest(PlayerState baseline) {
        observedState = new PlayerState(baseline);
        requestedState = null;
    }

    private boolean isSignificantChange(PlayerState local, PlayerState expected) {
        if (isSignificantPositionChange(local, expected)) {
            return true;
        }
        if (local.getPlaybackState() == expected.getPlaybackState()) {
            return false;
        }
        PlayerState baseline = requestedState == null ? observedState : requestedState;
        return baseline == null || local.getPlaybackState() != baseline.getPlaybackState();
    }

    private boolean isSignificantPositionChange(PlayerState local, PlayerState expected) {
        if (observedState == null) {
            return false;
        }
        if (positionDifference(local, expected) <= SEEK_THRESHOLD_SECONDS) {
            return false;
        }
        // Drift from a long-lived expectation alone is not a new user seek.
        return positionDifference(local, observedState) > SEEK_THRESHOLD_SECONDS;
    }

    private void updateServerState(PlayerState serverStatus) {
        retryPlayerUpdate = false;
        expectedState = new PlayerState(serverStatus);
        expectedState.setLastUpdateTime(getCurrentTime());
    }

    private boolean matches(PlayerState local, PlayerState expected) {
        return local.getPlaybackState() == expected.getPlaybackState()
                && positionDifference(local, expected) <= SEEK_THRESHOLD_SECONDS;
    }

    private double positionDifference(PlayerState local, PlayerState expected) {
        return Math.abs(local.getPosition() - PlayerStateUtils.advance(expected, getCurrentTime()).getPosition());
    }

    private PlayerState readPlayer() {
        try {
            PlayerState state = getPlayerStatus();
            state.setLastUpdateTime(getCurrentTime());
            if (requestedState != null && matches(state, requestedState)) {
                finishPlayerRequest(requestedState);
            }
            return state;
        } catch (IllegalStateException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ExecutionException) {
                cause = cause.getCause();
            }
            if (cause instanceof NoMediaLoadedException) {
                return null;
            }
            throw error;
        }
    }

    private void resetPlayback() {
        restoreSpeed(this::updatePlayerStatus);
        behindSince = -1;
        playbackSpeedReduced = false;
        expectedState = null;
        observedState = null;
        requestedState = null;
        roomStateReceived = false;
        retryPlayerUpdate = false;
        mediaAnnounced = false;
    }

    private void restoreSpeed(Consumer<PlayerState> update) {
        if (!playbackSpeedReduced) {
            return;
        }
        PlayerState local = readPlayer();
        if (local != null) {
            local.setPlaybackSpeed(1.0);
            update.accept(local);
        }
        playbackSpeedReduced = false;
    }

    @Override
    public void onStatusChange(PlayerState status) {
        PlayerState snapshot = new PlayerState(Objects.requireNonNull(status, "status"));
        executeEvent(() -> {
            log.debug("Player status event detected: {}", snapshot);
            publishLocalChange(snapshot);
        });
    }

    @Override
    protected void sendKeepAlive() {
        if (!session.isLoggedIn()) {
            return;
        }
        PlayerState local = readPlayer();
        announceMedia(local);
        state.reply(roomStateReceived && !retryPlayerUpdate ? local : null, Ping.getDefaultInstance());
    }

    @Override
    protected void onClose() {
        restoreSpeed(getPlayer()::updateStatus);
        session.setPassword(null);
    }

    /**
     * Python defaults, in seconds; immutable for the lifetime of a session.
     */
    @lombok.Value
    @Accessors(fluent = true)
    public static final class Thresholds {
        public static final Thresholds DEFAULT = new Thresholds(4.0, 5.0, 1.5);
        private final double rewind;
        private final double fastForward;
        private final double slowdown;

        public Thresholds(double rewind, double fastForward, double slowdown) {
            if (!Double.isFinite(rewind) || rewind < 3 || !Double.isFinite(fastForward) || fastForward < 4
                    || !Double.isFinite(slowdown) || slowdown < 1.3) {
                throw new IllegalArgumentException("Invalid Syncplay stateManager thresholds");
            }
            this.rewind = rewind;
            this.fastForward = fastForward;
            this.slowdown = slowdown;
        }
    }

}
