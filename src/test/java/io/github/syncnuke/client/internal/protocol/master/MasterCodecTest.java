package io.github.syncnuke.client.internal.protocol.master;

import io.github.syncnuke.client.internal.protocol.master.data.ConnectData;
import io.github.syncnuke.client.internal.protocol.master.data.JoinData;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MasterCodecTest {

    private final MasterCodec codec = new MasterCodec();

    @Test
    void encodesAndDecodesJoin() throws IOException {
        JoinData message = new JoinData("datasaver", "0.1.0", "røom", "secret");
        byte[] encoded = new byte[] {
                3,
                0, 9, 'd', 'a', 't', 'a', 's', 'a', 'v', 'e', 'r',
                0, 5, '0', '.', '1', '.', '0',
                0, 5, 'r', (byte) 0xc3, (byte) 0xb8, 'o', 'm',
                1, 0, 6, 's', 'e', 'c', 'r', 'e', 't'
        };

        assertArrayEquals(encoded, codec.encode(message));
        assertEquals(message, codec.decode(new ByteArrayInputStream(encoded)));
    }

    @Test
    void encodesAndDecodesConnect() throws IOException {
        ConnectData message = new ConnectData("nøde.example", 65535, "datasaver", "0.1.0");
        byte[] encoded = new byte[] {
                1,
                0, 13, 'n', (byte) 0xc3, (byte) 0xb8, 'd', 'e', '.',
                'e', 'x', 'a', 'm', 'p', 'l', 'e',
                (byte) 0xff, (byte) 0xff,
                0, 9, 'd', 'a', 't', 'a', 's', 'a', 'v', 'e', 'r',
                0, 5, '0', '.', '1', '.', '0'
        };

        assertArrayEquals(encoded, codec.encode(message));
        assertEquals(message, codec.decode(new ByteArrayInputStream(encoded)));
    }

    @Test
    void encodesEmptyConnect() {
        assertArrayEquals(
                new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 0},
                codec.encode(new ConnectData("", 0, "", ""))
        );
    }

}
