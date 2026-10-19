package io.github.syncnuke.client.data;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
public class RoomInfo {

    private final String room;
    private final List<String> users;
    private final Map<String, MediaInfo> files;
    private final String fileName;

    public RoomInfo(String room, List<String> users, Map<String, MediaInfo> files) {
        this(room, users, files, null);
    }

    public RoomInfo(String room, List<String> users) {
        this(room, users, java.util.Collections.emptyMap());
    }

}
