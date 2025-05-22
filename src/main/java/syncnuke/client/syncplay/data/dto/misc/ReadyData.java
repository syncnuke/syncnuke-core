package syncnuke.client.syncplay.data.dto.misc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Data;

@Data
@JsonRootName("ready")
public class ReadyData {
    private String username;
    @JsonProperty("isReady")
    private boolean ready;
    private boolean manuallyInitiated;
}
