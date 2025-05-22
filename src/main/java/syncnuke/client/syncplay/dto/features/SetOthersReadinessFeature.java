package syncnuke.client.syncplay.dto.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents the set others' readiness feature in Syncplay.
 * <p>
 * This feature defines whether a user can set the readiness state of other users.
 * </p>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "setOthersReadiness": true
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SetOthersReadinessFeature extends Feature {
    /**
     * Whether a user can set the readiness state of others.
     */
    private boolean setOthersReadiness;
}
