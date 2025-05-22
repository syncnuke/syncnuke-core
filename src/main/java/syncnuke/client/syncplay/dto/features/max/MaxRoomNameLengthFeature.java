package syncnuke.client.syncplay.dto.features.max;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import syncnuke.client.syncplay.dto.features.Feature;

/**
 * Represents the maximum room name length feature in Syncplay.
 * <p>
 * This feature defines the maximum allowed length of room names.
 * </p>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "maxRoomNameLength": 50
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MaxRoomNameLengthFeature extends Feature {

    /**
     * Maximum allowed length for room names.
     */
    private int maxRoomNameLength;
}
