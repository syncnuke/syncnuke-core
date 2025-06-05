package io.github.syncnuke.client.syncplay.data.dto.misc;

import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Data;

import java.util.List;

@Data
@JsonRootName("playlistChange")
public class PlaylistData {
    private String user;
    private List<String> files;
}
