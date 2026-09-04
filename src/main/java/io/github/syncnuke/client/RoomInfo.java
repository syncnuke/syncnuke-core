package io.github.syncnuke.client;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class RoomInfo {

    private final String room;
    private final List<String> users;

}
