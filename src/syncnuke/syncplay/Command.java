package syncnuke.syncplay;

import lombok.Getter;
import syncnuke.syncplay.data.BaseData;
import syncnuke.syncplay.data.HelloData;
import syncnuke.syncplay.data.SetData;
import syncnuke.syncplay.data.StateData;

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
