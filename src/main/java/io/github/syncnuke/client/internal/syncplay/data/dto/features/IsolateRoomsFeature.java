package io.github.syncnuke.client.internal.syncplay.data.dto.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents the isolate rooms feature in Syncplay.
 * <p>
 * This feature determines whether rooms are isolated from each other.
 * If true, users in different rooms cannot see each other.
 * </p>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "isolateRooms": true
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class IsolateRoomsFeature extends Feature {
    /**
     * Whether rooms are isolated.
     * <p>
     * If true, users in different rooms cannot see or interact with each other.
     * </p>
     */
    private boolean isolateRooms;
}
