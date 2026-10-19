package io.github.syncnuke.client.internal.protocol.syncplay.command;

import pl.syncplay.proto.SyncplayProto.Ping;

/** Syncplay's RTT moving average and estimate of server-to-client delay. */
final class SyncplayLatency {

    private double rtt;
    private double averageRtt;
    private double forwardDelay;

    double getRtt() {
        return rtt;
    }

    double getForwardDelay() {
        return forwardDelay;
    }

    void reset() {
        rtt = averageRtt = forwardDelay = 0;
    }

    void receive(Ping ping, double now) {
        double timestamp = ping.getClientLatencyCalculation();
        double senderRtt = ping.getServerRtt();
        double elapsed = now - timestamp;
        if (timestamp <= 0 || !Double.isFinite(elapsed) || elapsed < 0
                || !Double.isFinite(senderRtt) || senderRtt < 0) {
            return;
        }
        rtt = elapsed;
        if (averageRtt == 0) {
            averageRtt = rtt;
        }
        averageRtt = averageRtt * 0.85 + rtt * 0.15;
        forwardDelay = averageRtt / 2 + Math.max(0, rtt - senderRtt);
    }

}
