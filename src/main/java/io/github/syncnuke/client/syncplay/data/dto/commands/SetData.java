package io.github.syncnuke.client.syncplay.data.dto.commands;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.annotation.JsonView;
import lombok.Data;
import lombok.EqualsAndHashCode;
import io.github.syncnuke.client.syncplay.data.BaseData;
import io.github.syncnuke.client.syncplay.data.dto.FileData;
import io.github.syncnuke.client.syncplay.data.dto.RoomData;
import io.github.syncnuke.client.syncplay.data.dto.UserData;
import io.github.syncnuke.client.syncplay.data.dto.misc.ControllerAuthData;
import io.github.syncnuke.client.syncplay.data.dto.misc.PlaylistData;
import io.github.syncnuke.client.syncplay.data.dto.misc.PlaylistIndexData;
import io.github.syncnuke.client.syncplay.data.dto.misc.ReadyData;
import io.github.syncnuke.client.syncplay.data.dto.view.Views;

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
