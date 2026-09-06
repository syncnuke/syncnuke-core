package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConnectData implements BaseData {

    private Command command;
    private String host;
    private int port;

    public ConnectData(String host, int port) {
        this(Command.CONNECT, host, port);
    }

    @Override
    public Command getCommand() {
        return Command.CONNECT;
    }

}
