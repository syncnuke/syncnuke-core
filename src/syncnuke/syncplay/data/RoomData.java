package syncnuke.syncplay.data;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RoomData extends BaseData {
    private String name;

    public RoomData(String name) {
        this.name = name;
    }
}
