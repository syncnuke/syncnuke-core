package syncnuke.syncplay.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import syncnuke.syncplay.data.features.ReadinessFeature;
import syncnuke.syncplay.data.features.UiModeFeature;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonSerialize
@JsonRootName("Hello")
public class HelloData extends BaseData {

    private String username;
    private RoomData room;
    private String version = "1.2.255"; // Compatibility version
    @JsonProperty("realversion")
    private String realVersion = "1.3.0"; // Actual Syncplay version
    private Map<String, Object> features;

    public HelloData(String username, String room) {
        super();
        this.username = username;
        this.room = new RoomData(room);
        initFeatures();
    }

    private void initFeatures() {
        features = new HashMap<>();
        features.put("uiMode", new UiModeFeature("console"));
        features.put("readiness", new ReadinessFeature(false));
    }

}
