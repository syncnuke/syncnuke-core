package syncnuke.client.syncplay.data.commands;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.annotation.JsonView;
import lombok.Data;
import lombok.EqualsAndHashCode;
import syncnuke.client.syncplay.data.BaseData;
import syncnuke.client.syncplay.data.FileData;
import syncnuke.client.syncplay.data.RoomData;
import syncnuke.client.syncplay.data.UserData;
import syncnuke.client.syncplay.data.misc.ControllerAuthData;
import syncnuke.client.syncplay.data.misc.PlaylistData;
import syncnuke.client.syncplay.data.misc.PlaylistIndexData;
import syncnuke.client.syncplay.data.misc.ReadyData;
import syncnuke.client.syncplay.data.view.Views;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonRootName("Set")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SetData extends BaseData {
    @JsonView(Views.Client.class)
    private RoomData room;
    @JsonView(Views.Client.class)
    private FileData file;
    @JsonView(Views.Client.class)
    private ControllerAuthData controllerAuth;
    @JsonView(Views.Client.class)
    private ReadyData ready;

    @JsonView(Views.Server.class)
    private PlaylistData playlistChange;
    @JsonView(Views.Server.class)
    private PlaylistIndexData playlistIndex;
    @JsonProperty("user")
    @JsonView(Views.Server.class)
    private Map<String, UserData> users;

}
