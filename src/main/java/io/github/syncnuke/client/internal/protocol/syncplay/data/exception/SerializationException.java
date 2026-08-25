package io.github.syncnuke.client.internal.protocol.syncplay.data.exception;

public class SerializationException extends RuntimeException {

    public SerializationException(Throwable cause) {
        super("Failed to serialize data", cause);
    }

    public SerializationException(String message) {
        super(message);
    }

}
