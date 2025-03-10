package syncnuke.syncplay.data;

public class SerializationException extends RuntimeException {

    public SerializationException(Throwable cause) {
        super("Failed to serialize data", cause);
    }

}
