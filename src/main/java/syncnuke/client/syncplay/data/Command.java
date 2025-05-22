package syncnuke.client.syncplay.data;

import lombok.Getter;
import syncnuke.client.syncplay.data.dto.commands.HelloData;
import syncnuke.client.syncplay.data.dto.commands.SetData;
import syncnuke.client.syncplay.data.dto.commands.StateData;

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
