package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class JoinData implements BaseData {

    private Command command;
    private String username;
    private String room;
    private String password;

    public JoinData(String username, String room, String password) {
        this(Command.JOIN_ROOM, username, room, password);
    }

}
