package io.github.syncnuke.client.internal.syncplay.data;

import lombok.Getter;
import io.github.syncnuke.client.internal.syncplay.data.dto.commands.HelloData;
import io.github.syncnuke.client.internal.syncplay.data.dto.commands.SetData;
import io.github.syncnuke.client.internal.syncplay.data.dto.commands.StateData;

@Getter
public enum Command {
    HELLO("Hello", HelloData.class),
    STATE("State", StateData.class),
    SET("Set", SetData.class),
    ;

    private final Class<? extends BaseData> dataClass;
    private final String name;

    Command(String name, Class<? extends BaseData> dataClass) {
        this.name = name;
        this.dataClass = dataClass;
    }

    @Override
    public String toString() {
        return getName();
    }

}
