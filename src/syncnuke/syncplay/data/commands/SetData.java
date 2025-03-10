package syncnuke.syncplay.data.commands;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.EqualsAndHashCode;
import syncnuke.syncplay.data.BaseData;
import syncnuke.syncplay.data.FileData;
import syncnuke.syncplay.data.RoomData;
import syncnuke.syncplay.data.UserData;
import syncnuke.syncplay.data.misc.ControllerAuthData;
import syncnuke.syncplay.data.misc.PlaylistData;
import syncnuke.syncplay.data.misc.PlaylistIndexData;
import syncnuke.syncplay.data.misc.ReadyData;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonSerialize
@JsonRootName("Set")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SetData extends BaseData {
    private RoomData room;
    private FileData file;
    private ControllerAuthData controllerAuth;
    private ReadyData ready;
    private PlaylistData playlistChange;
    private PlaylistIndexData playlistIndex;
    @JsonProperty("user")
    private Map<String, UserData> users;
}
