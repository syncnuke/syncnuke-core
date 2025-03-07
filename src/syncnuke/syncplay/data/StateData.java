package syncnuke.syncplay.data;

import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonSerialize
@JsonRootName("State")
public class StateData extends BaseData {

    private PlayState playstate;
    private PingState ping;

    public StateData(double position, boolean paused, boolean doSeek) {
        this.playstate = new PlayState(position, paused, doSeek);
        this.ping = new PingState();
    }

    @Data
    public static class PlayState {
        private double position;
        private boolean paused;
        private boolean doSeek;

        public PlayState(double position, boolean paused, boolean doSeek) {
            this.position = position;
            this.paused = paused;
            this.doSeek = doSeek;
        }
    }

    @Data
    public static class PingState {
        private double latencyCalculation;
        private double clientLatencyCalculation;
        private double clientRtt;

        public PingState() {
            this.latencyCalculation = System.currentTimeMillis() / 1000.0; // Timestamp in seconds
            this.clientLatencyCalculation = this.latencyCalculation;
            this.clientRtt = 0; // Simplify RTT for now
        }
    }
}
