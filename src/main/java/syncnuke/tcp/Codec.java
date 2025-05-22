package syncnuke.tcp;

import java.io.IOException;
import java.io.InputStream;

public interface Codec<T> {

    byte[] encode(T value) throws IOException;
    T decode(InputStream in) throws IOException;

}
