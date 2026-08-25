package io.github.syncnuke.client.internal.protocol.syncplay.data.builder;

import pl.syncplay.proto.SyncplayProto.Features;
import pl.syncplay.proto.SyncplayProto.HelloMessage;
import pl.syncplay.proto.SyncplayProto.RoomInfo;

/**
 * Builds a fully-populated {@link HelloMessage} that matches the reference Syncplay client.
 *
 * Usage:
 * <pre>{@code
 * HelloMessage hello = HelloMessageBuilder
 *         .create("user", "room", "optional-password")
 *         .build();
 * }</pre>
 */
public final class HelloMessageBuilder {

    private static final String COMPAT_VERSION = "1.2.255";
    private static final String REAL_VERSION   = "1.7.0";

    private final HelloMessage.Builder builder;

    private HelloMessageBuilder(String username, String room, String password) {

        // Room info
        RoomInfo roomInfo = RoomInfo.newBuilder()
                .setName(room)
                .build();

        // Feature map identical to the Python reference client
        Features features = Features.newBuilder()
                .setFeatureList(true)
                .setReadiness(true)
                .setManagedRooms(false)
                .setChat(true)
                .build();

        this.builder = HelloMessage.newBuilder()
                .setUsername(username)
                .setRoom(roomInfo)
                .setVersion(COMPAT_VERSION)
                .setRealversion(REAL_VERSION)
                .setFeatures(features);

        if (password != null && !password.isEmpty()) {
            builder.setPassword(password);
        }
    }

    public static HelloMessageBuilder create(String username, String room) {
        return new HelloMessageBuilder(username, room, null);
    }

    public static HelloMessageBuilder create(String username, String room, String password) {
        return new HelloMessageBuilder(username, room, password);
    }

    public HelloMessage build() {
        return builder.build();
    }

}
