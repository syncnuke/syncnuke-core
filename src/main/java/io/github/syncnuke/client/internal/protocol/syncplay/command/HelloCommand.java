package io.github.syncnuke.client.internal.protocol.syncplay.command;

import io.github.syncnuke.client.internal.protocol.syncplay.SyncplayClient;
import io.github.syncnuke.client.internal.protocol.syncplay.data.HelloData;
import lombok.extern.slf4j.Slf4j;
import pl.syncplay.proto.SyncplayProto.*;

import java.math.BigInteger;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.Consumer;

@Slf4j
public final class HelloCommand {

    private final SyncplaySession session;
    private final RoomCommand rooms;
    private final Consumer<SyncplayMessage> send;

    public HelloCommand(SyncplaySession session, RoomCommand rooms, Consumer<SyncplayMessage> send) {
        this.session = session;
        this.rooms = rooms;
        this.send = send;
    }

    public void login(String username, String password) {
        session.username = username;
        session.requestedUsername = username;
        session.password = password == null || password.isEmpty() ? null : hashPassword(password);
        if (session.connectionReady) {
            send();
        }
    }

    public void handle(HelloMessage hello) throws ProtocolException {
        if (hello.getUsername().isEmpty() || hello.getRoom().getName().isEmpty()
                || (hello.getVersion().isEmpty() && hello.getRealversion().isEmpty())) {
            throw new ProtocolException("Invalid Syncplay greeting");
        }
        session.username = hello.getUsername();
        session.room = hello.getRoom().getName();
        session.controlledRoom = session.isControlledRoom();
        session.loggedIn = true;
        session.users.clear();
        session.users.add(session.username);
        log.debug("Command: {} User: {} Room: {}", "HELLO", session.username, session.room);
        log.info("{} joined the room.", session.username);
        rooms.requestList();
    }

    public void send() {
        HelloMessage.Builder hello = HelloMessage.newBuilder().setUsername(session.requestedUsername)
                .setRoom(RoomInfo.newBuilder().setName(session.room))
                .setVersion("1.2.255").setRealversion(SyncplayClient.VERSION);
        if (session.password != null) {
            hello.setPassword(session.password);
        }
        send.accept(SyncplayMessage.newBuilder().setHello(new HelloData(hello.build()).message()).build());
    }

    private static String hashPassword(String password) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(password.getBytes(StandardCharsets.UTF_8));
            return String.format(Locale.ROOT, "%032x", new BigInteger(1, digest));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Syncplay requires MD5 password hashing", error);
        }
    }

}
