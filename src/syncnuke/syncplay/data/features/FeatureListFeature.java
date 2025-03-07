package syncnuke.syncplay.data.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents the feature list support in Syncplay.
 * <p>
 * This feature allows the client to process and respond to a list of available features.
 * </p>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *     <li>If supported = true → The client can process feature lists from the server.</li>
 *     <li>If supported = false → The client cannot handle feature lists.</li>
 * </ul>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "featureList": {
 *     "supported": true
 *   }
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class FeatureListFeature extends Feature {
    private boolean supported;
}
