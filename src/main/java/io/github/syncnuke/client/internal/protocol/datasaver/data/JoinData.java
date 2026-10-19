package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@AllArgsConstructor
public class JoinData implements BaseData {

    private Command command;
    private String username;
    private String room;
    @ToString.Exclude
    private String password;

    public JoinData(String username, String room, String password) {
        this(Command.JOIN_ROOM, username, room, password);
    }

}
