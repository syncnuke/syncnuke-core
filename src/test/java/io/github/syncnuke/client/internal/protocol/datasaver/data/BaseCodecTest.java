package io.github.syncnuke.client.internal.protocol.datasaver.data;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseCodecTest {

    private final BaseCodec codec = new BaseCodec();

    @Test
    void decodesConsecutiveMessagesWithoutConsumingTheNextFrame() throws IOException {
        StateData first = new StateData(Command.UPDATE_STATE, State.PLAYING, 12.5, 1.5);
        StateData second = new StateData(Command.UPDATE_STATE, State.PAUSED, 42.25, 0.75);
        byte[] input = concatenate(codec.encode(first), codec.encode(second));
        InputStream stream = new ByteArrayInputStream(input);

        assertEquals(first, codec.decode(stream));
        assertEquals(second, codec.decode(stream));
    }

    @Test
    void waitsForACompleteMessageWhenTcpReadIsFragmented() throws IOException {
        StateData expected = new StateData(Command.UPDATE_STATE, State.PLAYING, 7.75, 2.0);
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

    @Test
    void encodesAndDecodesRoomJoin() throws IOException {
        JoinData message = new JoinData(Command.JOIN_ROOM, "usér", "røom");

        assertArrayEquals(
                new byte[] {
                        1,
                        0, 5, 'u', 's', (byte) 0xc3, (byte) 0xa9, 'r',
                        0, 5, 'r', (byte) 0xc3, (byte) 0xb8, 'o', 'm'
                },
                codec.encode(message)
        );
        assertEquals(
                message,
                codec.decode(new ByteArrayInputStream(codec.encode(message)))
        );
    }

    @Test
    void decodesConnectionRedirect() throws IOException {
        ConnectData message = new ConnectData("nøde.example", 65535);
        byte[] encoded = new byte[] {
                2, 0, 13, 'n', (byte) 0xc3, (byte) 0xb8, 'd', 'e', '.',
                'e', 'x', 'a', 'm', 'p', 'l', 'e', (byte) 0xff, (byte) 0xff
        };

        assertEquals(message, codec.decode(new ByteArrayInputStream(encoded)));
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
