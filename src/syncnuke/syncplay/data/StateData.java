package syncnuke.syncplay.data;

import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonSerialize
@JsonRootName("State")
public class StateData extends BaseData {

    private PlayState playstate;
    private PingState ping;
    private FileData file;

    public StateData(double position, boolean paused, boolean doSeek, FileData file) {
        this.playstate = new PlayState(position, paused, doSeek);
        this.ping = new PingState();
        this.file = file;
    }

    @Data
    @NoArgsConstructor
    public static class PlayState {
        private double position;
        private boolean paused;
        private boolean doSeek; // Whether to seek to the position
        private String setBy; // User who set the state

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
        private double serverRtt;

        public PingState() {
            this.latencyCalculation = System.currentTimeMillis() / 1000.0; // Timestamp in seconds
            this.clientLatencyCalculation = this.latencyCalculation;
        }
    }
}
