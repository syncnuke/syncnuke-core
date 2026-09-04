package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConnectData implements BaseData {

    private String host;
    private int port;

    @Override
    public Command getCommand() {
        return Command.CONNECT;
    }

}
