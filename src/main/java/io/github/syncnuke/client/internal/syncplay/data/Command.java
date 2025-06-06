package io.github.syncnuke.client.internal.syncplay.data;

import com.google.protobuf.Message;
import lombok.Getter;
import pl.syncplay.proto.SyncplayProto.HelloMessage;
import pl.syncplay.proto.SyncplayProto.SetCommand;
import pl.syncplay.proto.SyncplayProto.StateMessage;

@Getter
public enum Command {
    HELLO("Hello", HelloMessage.class),
    STATE("State", StateMessage.class),
    SET("Set", SetCommand.class),
    ;

    private final Class<? extends Message> dataClass;
    private final String name;

    Command(String name, Class<? extends Message> dataClass) {
        this.name = name;
        this.dataClass = dataClass;
    }

    @Override
    public String toString() {
        return getName();
    }

}
