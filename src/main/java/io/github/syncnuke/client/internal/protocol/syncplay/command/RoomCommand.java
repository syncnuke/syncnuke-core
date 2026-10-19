package io.github.syncnuke.client.internal.protocol.syncplay.command;

import io.github.syncnuke.client.data.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import pl.syncplay.proto.SyncplayProto.*;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
public final class RoomCommand {

    private final SyncplaySession session;
    private final Runnable resetPlayback;
    private final Consumer<SyncplayMessage> send;

    public RoomCommand(SyncplaySession session, Runnable resetPlayback, Consumer<SyncplayMessage> send) {
        this.session = session;
        this.resetPlayback = resetPlayback;
        this.send = send;
    }

    public void handleList(ListMessage rooms) {
        if (!session.loggedIn) {
            return;
        }
        Set<String> previousUsers = new LinkedHashSet<>(session.users);
        session.users.clear();
        session.files.keySet().removeIf(name -> !name.equals(session.username));
        for (RoomEntry entry : rooms.getRoomsList()) {
            if (!entry.getRoomName().equals(session.room)) {
                continue;
            }
            for (UserEntry user : entry.getUsersList()) {
                session.users.add(user.getUsername());
                if (user.hasFile()) session.files.put(user.getUsername(), new MediaInfo(user.getFile().getName(), user.getFile().getDuration()));
                if (!previousUsers.contains(user.getUsername())) {
                    log.debug("Command: {} User: {} Room: {}", "LIST", user.getUsername(), session.room);
                    log.info("{} joined the room.", user.getUsername());
                }
                if (user.getUsername().equals(session.username)) {
                    session.controller = user.getController();
                }
            }
        }
        session.users.add(session.username);
    }

    public void handleSettings(SetCommand settings) {
        if (!session.loggedIn) {
            return;
        }
        if (settings.hasRoom()) {
            updateRoom(settings.getRoom().getName());
        }
        settings.getUserMap().forEach(this::handleUserSetting);
        settings.getUserMap().forEach((name, user) -> {
            if (!session.users.contains(name)) return;
            if (user.hasFile()) session.files.put(name, new MediaInfo(user.getFile().getName(), user.getFile().getDuration()));
            else {
                var rawUser = settings.getExtensions().getFieldsOrDefault("user", com.google.protobuf.Value.getDefaultInstance())
                        .getStructValue().getFieldsOrDefault(name, com.google.protobuf.Value.getDefaultInstance()).getStructValue();
                if (rawUser.containsFields("file")) session.files.remove(name);
            }
        });
        // Controller status only gates follower catch-up in managed rooms.
        if (settings.hasControllerResult() && settings.getControllerResult().getUser().equals(session.username)) {
            session.controller = settings.getControllerResult().getSuccess();
        }
    }

    private void handleUserSetting(String name, UserSetting user) {
        log.debug("Command: {} User: {} Room: {}", "SET", name,
                user.hasRoom() ? user.getRoom().getName() : session.room);
        if (user.getEvent() == UserSetting.Event.LEFT) {
            log.info("{} left the room.", name);
            session.users.remove(name);
            session.files.remove(name);
            return;
        }
        if (!user.hasRoom()) {
            return;
        }
        String userRoom = user.getRoom().getName();
        if (Objects.equals(session.room, userRoom) && !session.users.contains(name)) {
            log.info("{} joined the room.", name);
        }
        if (name.equals(session.username)) {
            updateRoom(userRoom);
        }
        if (Objects.equals(session.room, userRoom)) {
            session.users.add(name);
        } else {
            session.users.remove(name);
            session.files.remove(name);
        }
    }

    private void updateRoom(String name) {
        if (Objects.equals(session.room, name)) {
            return;
        }
        session.room = name;
        resetRoomState();
        requestList();
    }

    public void resetRoomState() {
        session.users.clear();
        session.files.clear();
        if (session.username != null) {
            session.users.add(session.username);
        }
        resetPlayback.run();
        session.controller = false;
        session.controlledRoom = session.isControlledRoom();
        session.ping.reset();
        session.clientIgnoring = session.serverIgnoring = 0;
    }

    public void changeRoom() {
        send.accept(SyncplayMessage.newBuilder().setSet(SetCommand.newBuilder()
                .setRoom(RoomInfo.newBuilder().setName(session.room))).build());
        requestList();
    }

    void requestList() {
        send.accept(SyncplayMessage.newBuilder().setList(ListMessage.getDefaultInstance()).build());
    }

}
