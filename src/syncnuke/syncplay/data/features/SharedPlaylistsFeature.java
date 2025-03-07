package syncnuke.syncplay.data.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents the shared playlists feature in Syncplay.
 * <p>
 * This feature allows the client to support synchronized playlists across users in the same room.
 * </p>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *     <li>If supported = true → The client supports shared playlists.</li>
 *     <li>If supported = false → The client does not support shared playlists.</li>
 * </ul>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "sharedPlaylists": {
 *     "supported": true
 *   }
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SharedPlaylistsFeature extends Feature {
    private boolean supported;
}
