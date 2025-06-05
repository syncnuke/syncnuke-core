package io.github.syncnuke.client.internal.syncplay.data.dto.misc;

import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Data;

@Data
@JsonRootName("playlistIndex")
public class PlaylistIndexData {
    private String user;
    private int index;
}
