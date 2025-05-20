package syncnuke.client.syncplay.data.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents the persistent rooms feature in Syncplay.
 * <p>
 * This feature allows rooms to persist even when all clients leave.
 * Persistent rooms remain available for clients to join until they are explicitly deleted.
 * </p>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *     <li>If persistentRooms = true → The client supports persistent rooms.</li>
 *     <li>If persistentRooms = false → The client does not support persistent rooms.</li>
 * </ul>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "persistentRooms": true
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PersistentRoomsFeature extends Feature {
    private boolean persistentRooms;
}
