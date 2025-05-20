package syncnuke.client.syncplay.data.commands;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.annotation.JsonView;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import syncnuke.client.syncplay.data.BaseData;
import syncnuke.client.syncplay.data.view.Views;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonRootName("State")
public class StateData extends BaseData {

    @JsonView(Views.Client.class)
    private PlayState playstate;
    @JsonView(Views.Client.class)
    private PingState ping;
    @JsonView(Views.Client.class)
    private IgnoringOnTheFly ignoringOnTheFly;

    public StateData(double position, boolean paused, boolean doSeek, String setBy) {
        this.playstate = new PlayState(position, paused, doSeek, setBy);
        this.ping = new PingState();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlayState {
        @JsonView(Views.Client.class)
        private double position;
        @JsonView(Views.Client.class)
        private boolean paused;
        @JsonView(Views.Client.class)
        private boolean doSeek; // Whether to seek to the position

        @JsonView(Views.Server.class)
        private String setBy; // User who set the state
    }

    @Data
    public static class PingState {
        @JsonView(Views.Client.class)
        private double latencyCalculation;
        @JsonView(Views.Client.class)
        private double clientLatencyCalculation;
        @JsonView(Views.Client.class)
        private double clientRtt;

        @JsonView(Views.Server.class)
        private double serverRtt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IgnoringOnTheFly {
        @JsonView(Views.Client.class)
        private int client;
        @JsonView(Views.Client.class)
        private int server;
    }
}
