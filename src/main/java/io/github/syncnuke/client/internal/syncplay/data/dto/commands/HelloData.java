package io.github.syncnuke.client.internal.syncplay.data.dto.commands;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import io.github.syncnuke.client.internal.syncplay.data.BaseData;
import io.github.syncnuke.client.internal.syncplay.data.dto.RoomData;
import io.github.syncnuke.client.internal.syncplay.data.dto.features.ReadinessFeature;
import io.github.syncnuke.client.internal.syncplay.data.dto.features.UiModeFeature;
import io.github.syncnuke.client.internal.syncplay.data.dto.view.Views;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonRootName("Hello")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HelloData extends BaseData {

    @JsonView(Views.Client.class)
    private String username;
    @JsonView(Views.Client.class)
    private RoomData room;
    @JsonView(Views.Client.class)
    private String version = "1.2.255"; // Compatibility version
    @JsonView(Views.Client.class)
    private Map<String, Object> features;

    @JsonProperty("realversion")
    @JsonView(Views.Client.class)
    private String realVersion = "1.7.0"; // Actual Syncplay version
    @JsonView(Views.Server.class)
    private String motd;

    public HelloData(String username, String room) {
        this.username = username;
        this.room = new RoomData(room);
        initFeatures();
    }

    private void initFeatures() {
        features = new HashMap<>();
//        features.put("uiMode", new UiModeFeature("console"));
        features.put("featureList", true);
        features.put("readiness", true);
        features.put("managedRooms", false);
        features.put("chat", true);
    }

}
