package io.github.syncnuke.client.internal.protocol.syncplay.data;

import lombok.Value;
import lombok.experimental.Accessors;
import pl.syncplay.proto.SyncplayProto.Features;
import pl.syncplay.proto.SyncplayProto.HelloMessage;

@Value
@Accessors(fluent = true)
public class HelloData {

    private static final Features FEATURES = Features.newBuilder()
            .setFeatureList(true)
            .setSharedPlaylists(false)
            .setChat(false)
            .setReadiness(false)
            .setManagedRooms(false)
            .setPersistentRooms(false)
            .setSetOthersReadiness(false)
            .build();

    HelloMessage message;

    public HelloData(HelloMessage message) {
        this.message = message.toBuilder().setFeatures(FEATURES).build();
    }

}
