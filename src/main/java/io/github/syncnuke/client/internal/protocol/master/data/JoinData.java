package io.github.syncnuke.client.internal.protocol.master.data;

import lombok.Data;

@Data
public class JoinData implements BaseData {

    private final Command command = Command.JOIN;
    private final String protocol;
    private final String version;
    private final String room;
    private final String password;

}
