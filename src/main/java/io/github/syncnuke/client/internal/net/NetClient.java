package io.github.syncnuke.client.internal.net;

import java.io.Closeable;

public interface NetClient<T> extends Closeable {

    /**
     * Connect to the server using the specified host and port.
     * Expects a codec to handle data serialization/deserialization.
     * @param host  the server host
     * @param port  the server port
     * @param codec the codec to use for encoding/decoding data
     */
    void connect(String host, int port, Codec<T> codec);

    /**
     * Sends the provided data to the server.
     * @param data the data to send
     */
    void send(T data);
    
    /**
     * Add a listener to trigger when data is received from the server.
     * @param listener The listener to trigger
     */
    void addListener(NetListener<T> listener);
    
    /**
     * Remove a previously added listener
     * @param listener The listener to remove
     * @return true if the listener was found and removed
     */
    boolean removeListener(NetListener<T> listener);

}
