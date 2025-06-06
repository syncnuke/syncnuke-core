package io.github.syncnuke.client.internal.syncplay.data;

import io.github.syncnuke.client.internal.tcp.Codec;

import java.io.*;

public class StringCodec implements Codec<String> {

    private final String LINE_SEPARATOR = "\r\n";

    @Override
    public byte[] encode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Attempting to encode a null value");
        }
        String msg = value + LINE_SEPARATOR;
        return msg.getBytes();
    }

    @Override
    public String decode(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line = reader.readLine();
        if (line == null) {
            throw new EOFException("End of stream reached");
        }
        return line;
    }

}
