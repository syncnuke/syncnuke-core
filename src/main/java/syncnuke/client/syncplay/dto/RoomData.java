package syncnuke.client.syncplay.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RoomData extends BaseData {
    private String name;

    public RoomData(String name) {
        this.name = name;
    }
}
