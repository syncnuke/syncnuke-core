package syncnuke.client.syncplay.data.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents the chat feature in Syncplay.
 * <p>
 * This feature allows the client to send and receive chat messages.
 * If supported, the server will broadcast chat messages to all clients in the same room.
 * </p>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *     <li>If chat = true → The client can send and receive chat messages.</li>
 *     <li>If chat = false → Chat functionality is disabled.</li>
 * </ul>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>
 * {
 *   "chat": true
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatFeature extends Feature {
    private boolean chat;
}
