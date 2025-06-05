package io.github.syncnuke.client.internal.syncplay.data.dto.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents the readiness state feature in Syncplay.
 * <p>
 * This feature allows the client to signal whether they are ready to start playback.
 * The server can broadcast this state to other clients in the same room.
 * </p>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *     <li>If readiness = true → The client supports readiness state.</li>
 *     <li>If readiness = false → The client does not support readiness state.</li>
 * </ul>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "readiness": {
 *     "readiness": true
 *   }
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ReadinessFeature extends Feature {
    private boolean readiness;
}
