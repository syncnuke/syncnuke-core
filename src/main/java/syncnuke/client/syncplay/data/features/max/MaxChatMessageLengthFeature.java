package syncnuke.client.syncplay.data.features.max;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import syncnuke.client.syncplay.data.features.Feature;

/**
 * Represents the maximum chat message length feature in Syncplay.
 * <p>
 * This feature defines the maximum length of chat messages supported by the client.
 * </p>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *     <li>A value greater than 0 → Maximum allowed length of a chat message.</li>
 *     <li>A value of 0 → Chat is disabled.</li>
 * </ul>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "maxChatMessageLength": 300
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MaxChatMessageLengthFeature extends Feature {

    /**
     * Maximum allowed length of chat messages.
     */
    private int maxChatMessageLength;
}
