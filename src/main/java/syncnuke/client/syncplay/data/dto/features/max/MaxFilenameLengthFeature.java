package syncnuke.client.syncplay.data.dto.features.max;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import syncnuke.client.syncplay.data.dto.features.Feature;

/**
 * Represents the maximum filename length feature in Syncplay.
 * <p>
 * This feature defines the maximum allowed length of media filenames.
 * </p>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "maxFilenameLength": 255
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MaxFilenameLengthFeature extends Feature {

    /**
     * Maximum allowed length for filenames.
     */
    private int maxFilenameLength;
}
