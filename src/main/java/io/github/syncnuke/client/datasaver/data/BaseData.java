package io.github.syncnuke.client.datasaver.data;

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

}
