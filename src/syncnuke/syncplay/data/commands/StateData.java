package syncnuke.syncplay.data.commands;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import syncnuke.syncplay.data.BaseData;
import syncnuke.syncplay.data.FileData;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonRootName("State")
public class StateData extends BaseData {

    private PlayState playstate;
    private PingState ping;
    private FileData file;
    private IgnoringOnTheFly ignoringOnTheFly;

    public StateData(double position, boolean paused, boolean doSeek, String setBy, FileData file) {
        this.playstate = new PlayState(position, paused, doSeek, setBy);
        this.ping = new PingState();
        this.file = file;
        this.ignoringOnTheFly = new IgnoringOnTheFly();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlayState {
        private double position;
        private boolean paused;
        private boolean doSeek; // Whether to seek to the position
        private String setBy; // User who set the state
    }

    @Data
    public static class PingState {
        private double latencyCalculation;
        private double clientLatencyCalculation;
        private double clientRtt;
        private double serverRtt;
    }

    @Data
    @NoArgsConstructor
    public static class IgnoringOnTheFly {
        private int client;
        private int server;
    }
}
