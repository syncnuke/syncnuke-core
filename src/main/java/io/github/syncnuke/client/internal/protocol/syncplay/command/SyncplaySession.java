package io.github.syncnuke.client.internal.protocol.syncplay.command;

import io.github.syncnuke.client.data.RoomInfo;
import io.github.syncnuke.client.data.MediaInfo;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Mutable protocol state, confined to SyncClient's event queue. */
public final class SyncplaySession {

    @Getter
    String requestedUsername;
    @Getter
    String username;
    @Setter
    String password;
    @Setter
    String room;
    final Set<String> users = new LinkedHashSet<>();
    final Map<String, MediaInfo> files = new LinkedHashMap<>();

    @Setter
    boolean connectionReady = true;
    @Getter
    @Setter
    boolean loggedIn;

    boolean controlledRoom;
    boolean controller;

    final SyncplayLatency ping = new SyncplayLatency();
    int clientIgnoring;
    int serverIgnoring;

    public RoomInfo getRoomInfo() {
        return new RoomInfo(room, new ArrayList<>(users), new LinkedHashMap<>(files));
    }

    public void setMedia(MediaInfo media) {
        if (media == null) files.remove(username);
        else files.put(username, media);
    }

    public boolean isFollower() {
        return !controller && controlledRoom;
    }

    boolean isControlledRoom() {
        return room != null && room.matches("^\\+(.*):([A-Za-z0-9_]{12})$");
    }

}
