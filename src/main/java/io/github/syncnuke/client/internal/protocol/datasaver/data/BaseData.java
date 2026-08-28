package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BaseData {

    private Command command;
    private State state;
    private double position;
    private double playbackSpeed;

}
