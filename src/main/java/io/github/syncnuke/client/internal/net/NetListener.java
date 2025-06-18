package io.github.syncnuke.client.internal.net;

/**
 * Interface for listening to responses from the network client
 * @param <T> The type of data received
 */
public interface NetListener<T> {
    /**
     * Called when a response is received
     * @param data The response data
     */
    void onResponse(T data);
}
