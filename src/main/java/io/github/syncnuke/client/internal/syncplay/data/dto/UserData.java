package io.github.syncnuke.client.internal.syncplay.data.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonRootName("user")
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserData {
    private RoomData room;
    private FileData file;
}
