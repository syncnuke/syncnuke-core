package io.github.syncnuke.client.internal.protocol.master.data;

import lombok.Data;

@Data
public class ConnectData implements BaseData {

    private final Command command = Command.CONNECT;
    private final String host;
    private final int port;
    private final String protocol;
    private final String version;

}
