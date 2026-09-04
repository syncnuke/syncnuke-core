package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LeaveData implements BaseData {

    private String username;
    private String room;

    @Override
    public Command getCommand() {
        return Command.LEAVE_ROOM;
    }

}
