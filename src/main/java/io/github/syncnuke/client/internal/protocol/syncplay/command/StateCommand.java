package io.github.syncnuke.client.internal.protocol.syncplay.command;

import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import lombok.extern.slf4j.Slf4j;
import pl.syncplay.proto.SyncplayProto.*;

import java.net.ProtocolException;
import java.util.function.Consumer;

/** Handles State wire messages, latency samples and acknowledgement counters. */
@Slf4j
public final class StateCommand {

    private final SyncplaySession session;
    private final TimingService timingService;
    private final Consumer<SyncplayMessage> send;

    public StateCommand(SyncplaySession session, TimingService timingService, Consumer<SyncplayMessage> send) {
        this.session = session;
        this.timingService = timingService;
        this.send = send;
    }

    public long handle(StateMessage state) {
        acknowledge(state.getIgnoringOnTheFly());
        long now = timingService.getCurrentTime();
        session.ping.receive(state.getPing(), now / 1000.0);
        return now;
    }

    private void acknowledge(Ignoring ignore) {
        if (ignore.hasServer()) {
            session.serverIgnoring = ignore.getServer();
            session.clientIgnoring = 0;
        } else if (ignore.hasClient() && ignore.getClient() == session.clientIgnoring) {
            session.clientIgnoring = 0;
        }
    }

    public PlayerState readServerState(StateMessage state, long now) throws ProtocolException {
        PlayState playstate = state.getPlaystate();
        if (session.clientIgnoring != 0 || !playstate.hasPaused()) {
            return null;
        }
        double position = playstate.getPosition();
        if (!Double.isFinite(position) || position < 0) {
            throw new ProtocolException("Invalid Syncplay position");
        }
        PlayerState global = new PlayerState();
        global.setPosition(position + (playstate.getPaused() || !state.hasPing() ? 0 : session.ping.getForwardDelay()));
        global.setPlaybackState(playstate.getPaused() ? PlaybackState.PAUSED : PlaybackState.PLAYING);
        global.setLastUpdateTime(now);
        return global;
    }

    public void reply(PlayerState local, Ping incomingPing) {
        sendState(local, false, false, incomingPing);
    }

    public void sendChange(PlayerState local, boolean seek) {
        sendState(local, seek, true, Ping.getDefaultInstance());
    }

    private void sendState(PlayerState local, boolean seek, boolean changed, Ping incomingPing) {
        StateMessage.Builder state = StateMessage.newBuilder();
        if (canReportPlayback(local, changed)) {
            state.setPlaystate(PlayState.newBuilder().setPosition(local.getPosition())
                    .setPaused(local.getPlaybackState() == PlaybackState.PAUSED).setDoSeek(seek));
            log.info("Sending state: status={}, progress={}", local.getPlaybackState(), local.getPosition());
        }
        state.setPing(createPing(incomingPing));
        if (changed) {
            session.clientIgnoring++;
        }
        addAcknowledgements(state);
        send.accept(SyncplayMessage.newBuilder().setState(state).build());
    }

    private boolean canReportPlayback(PlayerState local, boolean changed) {
        return local != null && (changed || session.clientIgnoring == 0 || session.serverIgnoring != 0);
    }

    private Ping.Builder createPing(Ping incoming) {
        Ping.Builder timing = Ping.newBuilder().setClientLatencyCalculation(timingService.getCurrentTime() / 1000.0)
                .setClientRtt(session.ping.getRtt());
        if (incoming.hasLatencyCalculation()) {
            timing.setLatencyCalculation(incoming.getLatencyCalculation());
        }
        return timing;
    }

    private void addAcknowledgements(StateMessage.Builder state) {
        if (session.clientIgnoring == 0 && session.serverIgnoring == 0) {
            return;
        }
        Ignoring.Builder ignore = state.getIgnoringOnTheFlyBuilder();
        if (session.serverIgnoring != 0) {
            ignore.setServer(session.serverIgnoring);
            session.serverIgnoring = 0;
        }
        if (session.clientIgnoring != 0) {
            ignore.setClient(session.clientIgnoring);
        }
    }

}
