package syncnuke.syncplay.data.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents the UI mode feature in Syncplay.
 * <p>
 * This feature defines the type of user interface used by the client.
 * </p>
 *
 * <p><b>Possible Values:</b></p>
 * <ul>
 *     <li>"graphical" → Graphical user interface (GUI)</li>
 *     <li>"console" → Text-based console interface</li>
 *     <li>"unknown" → UI type is unknown</li>
 * </ul>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "uiMode": {
 *     "mode": "graphical"
 *   }
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UiModeFeature extends Feature {
    /**
     * The type of UI used by the client.
     * <p>
     * Valid values:
     * <ul>
     *     <li>"graphical" → GUI</li>
     *     <li>"console" → Console-based UI</li>
     *     <li>"unknown" → Unknown UI type</li>
     * </ul>
     * </p>
     */
    private String mode;
}
