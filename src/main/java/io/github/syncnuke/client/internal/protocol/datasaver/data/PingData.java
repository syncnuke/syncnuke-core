package io.github.syncnuke.client.internal.protocol.datasaver.data;

public class PingData implements BaseData {

    @Override
    public Command getCommand() {
        return Command.PING;
    }

}
