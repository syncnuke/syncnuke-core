package io.github.syncnuke.client.internal.syncplay.data.dto.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents the managed rooms feature in Syncplay.
 * <p>
 * This feature allows the client to support controlled playback,
 * where a controller can manage playback (e.g., play, pause, seek) for other clients.
 * </p>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *     <li>If managedRooms = true → The client supports managed rooms and controlled playback.</li>
 *     <li>If managedRooms = false → The client does not support managed rooms.</li>
 * </ul>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "managedRooms": true
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ManagedRoomsFeature extends Feature {
    private boolean managedRooms;
}
