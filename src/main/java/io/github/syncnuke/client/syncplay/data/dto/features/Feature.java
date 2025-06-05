package io.github.syncnuke.client.syncplay.data.dto.features;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import io.github.syncnuke.client.syncplay.data.dto.features.max.MaxChatMessageLengthFeature;
import io.github.syncnuke.client.syncplay.data.dto.features.max.MaxFilenameLengthFeature;
import io.github.syncnuke.client.syncplay.data.dto.features.max.MaxRoomNameLengthFeature;
import io.github.syncnuke.client.syncplay.data.dto.features.max.MaxUsernameLengthFeature;

import java.io.Serializable;

@JsonSubTypes({
        @JsonSubTypes.Type(value = ReadinessFeature.class, name = "readiness"),
        @JsonSubTypes.Type(value = ChatFeature.class, name = "chat"),
        @JsonSubTypes.Type(value = ManagedRoomsFeature.class, name = "managedRooms"),
        @JsonSubTypes.Type(value = PersistentRoomsFeature.class, name = "persistentRooms"),
        @JsonSubTypes.Type(value = SharedPlaylistsFeature.class, name = "sharedPlaylists"),
        @JsonSubTypes.Type(value = FeatureListFeature.class, name = "featureList"),
        @JsonSubTypes.Type(value = UiModeFeature.class, name = "uiMode"),
        @JsonSubTypes.Type(value = MaxChatMessageLengthFeature.class, name = "maxChatMessageLength"),
        @JsonSubTypes.Type(value = MaxUsernameLengthFeature.class, name = "maxUsernameLength"),
        @JsonSubTypes.Type(value = MaxRoomNameLengthFeature.class, name = "maxRoomNameLength"),
        @JsonSubTypes.Type(value = MaxFilenameLengthFeature.class, name = "maxFilenameLength"),
        @JsonSubTypes.Type(value = IsolateRoomsFeature.class, name = "isolateRooms"),
        @JsonSubTypes.Type(value = SetOthersReadinessFeature.class, name = "setOthersReadiness")
})
public abstract class Feature implements Serializable {
}
