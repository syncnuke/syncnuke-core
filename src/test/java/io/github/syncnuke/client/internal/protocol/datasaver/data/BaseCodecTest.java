package io.github.syncnuke.client.internal.protocol.datasaver.data;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseCodecTest {

    private final BaseCodec codec = new BaseCodec();

    @Test
    void decodesConsecutiveMessagesWithoutConsumingTheNextFrame() throws IOException {
        BaseData first = new BaseData(Command.UPDATE_STATE, State.PLAYING, 12.5);
        BaseData second = new BaseData(Command.UPDATE_STATE, State.PAUSED, 42.25);
        byte[] input = concatenate(codec.encode(first), codec.encode(second));
        InputStream stream = new ByteArrayInputStream(input);

        assertEquals(first, codec.decode(stream));
        assertEquals(second, codec.decode(stream));
    }

    @Test
    void waitsForACompleteMessageWhenTcpReadIsFragmented() throws IOException {
        BaseData expected = new BaseData(Command.UPDATE_STATE, State.PLAYING, 7.75);
        InputStream stream = new FilterInputStream(
                new ByteArrayInputStream(codec.encode(expected))
        ) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return super.read(bytes, offset, Math.min(length, 3));
            }
        };

        assertEquals(expected, codec.decode(stream));
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
