package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LeaveData implements BaseData {

    private Command command;
    private String username;
    private String room;
    private String password;

    public LeaveData(String username, String room, String password) {
        this(Command.LEAVE_ROOM, username, room, password);
    }

    @Override
    public Command getCommand() {
        return Command.LEAVE_ROOM;
    }

}
