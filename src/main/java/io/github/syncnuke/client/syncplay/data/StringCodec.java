package io.github.syncnuke.client.syncplay.data;

import io.github.syncnuke.tcp.Codec;

import java.io.*;

public class StringCodec implements Codec<String> {

    @Override
    public byte[] encode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Attempting to encode a null value");
        }
        return value.getBytes();
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
