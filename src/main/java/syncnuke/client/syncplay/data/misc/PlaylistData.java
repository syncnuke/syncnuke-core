package syncnuke.client.syncplay.data.misc;

import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Data;

import java.util.List;

@Data
@JsonRootName("playlistChange")
public class PlaylistData {
    private String user;
    private List<String> files;
}
