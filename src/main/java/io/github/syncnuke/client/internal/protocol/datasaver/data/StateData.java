package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StateData implements BaseData {

    private Command command;
    private State state;
    private double position;
    private double playbackSpeed;
    // Optional field to be populated when the file changes
    private String fileName;

    public StateData(Command command, State state, double position, double playbackSpeed) {
        this(command, state, position, playbackSpeed, null);
    }

}
