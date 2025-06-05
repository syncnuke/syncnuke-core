package io.github.syncnuke.client.datasaver.data;

import io.github.syncnuke.tcp.Codec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BaseCodec implements Codec<BaseData> {

    @Override
    public byte[] encode(BaseData value) {
        if (value == null) {
            return new byte[0];
        }

        byte[] bytes = new byte[2 + Double.BYTES];
        bytes[0] = value.getCommand().getCode();
        bytes[1] = value.getState().getCode();
        insertDouble(bytes, 2, value.getPosition());

        return bytes;
    }

    @Override
    public BaseData decode(InputStream in) throws IOException {
        byte[] bytes = new byte[3 + Double.BYTES];
        int bytesRead = in.read(bytes);
        if (bytesRead == -1) {
            throw new IOException("End of stream reached");
        }

        Command command = Command.fromCode(bytes[0]);
        State state = State.fromCode(bytes[1]);
        double position = extractDouble(bytes, 2);

        return new BaseData(command, state, position);
    }

    @SuppressWarnings("SameParameterValue")
    private static void insertDouble(byte[] packet, int start, double value) {
        ByteBuffer.wrap(packet, start, Double.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putDouble(value);
    }

    @SuppressWarnings("SameParameterValue")
    private static double extractDouble(byte[] packet, int start) {
        return ByteBuffer.wrap(packet, start, Double.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getDouble();
    }

}
