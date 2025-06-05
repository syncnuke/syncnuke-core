package io.github.syncnuke.client.syncplay.data.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import io.github.syncnuke.client.syncplay.data.BaseData;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RoomData extends BaseData {
    private String name;

    public RoomData(String name) {
        this.name = name;
    }
}
