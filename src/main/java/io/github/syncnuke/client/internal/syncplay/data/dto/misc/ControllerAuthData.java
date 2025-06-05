package io.github.syncnuke.client.internal.syncplay.data.dto.misc;

import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Data;

@Data
@JsonRootName("controllerAuth")
public class ControllerAuthData {
    private boolean success;
    private String user;
    private String room;
}
