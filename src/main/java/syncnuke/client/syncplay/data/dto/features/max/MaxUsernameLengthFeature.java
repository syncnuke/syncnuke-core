package syncnuke.client.syncplay.data.dto.features.max;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import syncnuke.client.syncplay.data.dto.features.Feature;

/**
 * Represents the maximum username length feature in Syncplay.
 * <p>
 * This feature defines the maximum allowed length of usernames.
 * </p>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "maxUsernameLength": 20
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MaxUsernameLengthFeature extends Feature {

    /**
     * Maximum allowed length for usernames.
     */
    private int maxUsernameLength;
}
