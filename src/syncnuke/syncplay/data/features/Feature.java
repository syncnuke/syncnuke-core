package syncnuke.syncplay.data.features;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import syncnuke.syncplay.data.features.max.*;

import java.io.Serializable;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type")
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
        @JsonSubTypes.Type(value = MaxFilenameLengthFeature.class, name = "maxFilenameLength")
})
public abstract class Feature implements Serializable {
}
