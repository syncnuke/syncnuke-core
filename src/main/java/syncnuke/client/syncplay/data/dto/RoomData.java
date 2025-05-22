package syncnuke.client.syncplay.data.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import syncnuke.client.syncplay.data.BaseData;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RoomData extends BaseData {
    private String name;

    public RoomData(String name) {
        this.name = name;
    }
}
