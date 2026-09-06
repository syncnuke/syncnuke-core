package io.github.syncnuke.client.internal.protocol.master;

import io.github.syncnuke.client.internal.net.Codec;
import io.github.syncnuke.client.internal.protocol.master.data.ConnectData;
import io.github.syncnuke.client.internal.protocol.master.data.JoinData;
import io.github.syncnuke.client.internal.protocol.master.data.Command;
import io.github.syncnuke.client.internal.protocol.master.data.BaseData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class MasterCodec implements Codec<BaseData> {

    @Override
    public byte[] encode(BaseData value) {
        if (value == null) {
            return new byte[0];
        }
        return switch (value.getCommand()) {
            case JOIN -> encodeJoin((JoinData) value);
            case CONNECT -> encodeConnect((ConnectData) value);
        };
    }

    private byte[] encodeJoin(JoinData value) {
        byte[] protocol = encodeString(value.getProtocol());
        byte[] version = encodeString(value.getVersion());
        byte[] room = encodeString(value.getRoom());
        byte[] password = encodeOptionalString(value.getPassword());
        return ByteBuffer.allocate(1 + protocol.length + version.length + room.length + password.length)
                .put(Command.JOIN.getCode())
                .put(protocol)
                .put(version)
                .put(room)
                .put(password)
                .array();
    }

    private byte[] encodeConnect(ConnectData value) {
        if (value.getPort() < 0 || value.getPort() > 0xffff) {
            throw new IllegalArgumentException("Port is outside the unsigned 16-bit range");
        }
        byte[] host = encodeString(value.getHost());
        byte[] protocol = encodeString(value.getProtocol());
        byte[] version = encodeString(value.getVersion());
        return ByteBuffer.allocate(1 + host.length + Short.BYTES + protocol.length + version.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put(Command.CONNECT.getCode())
                .put(host)
                .putShort((short) value.getPort())
                .put(protocol)
                .put(version)
                .array();
    }

    @Override
    public BaseData decode(InputStream in) throws IOException {
        int code = in.read();
        if (code == -1) {
            throw new IOException("End of stream reached");
        }
        return switch (Command.fromCode((byte) code)) {
            case JOIN -> new JoinData(
                    decodeString(in),
                    decodeString(in),
                    decodeString(in),
                    decodeOptionalString(in)
            );
            case CONNECT -> new ConnectData(
                    decodeString(in),
                    decodePort(in),
                    decodeString(in),
                    decodeString(in)
            );
        };
    }

    private static byte[] encodeString(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 0xffff) {
            throw new IllegalArgumentException("String is too long");
        }
        return ByteBuffer.allocate(Short.BYTES + encoded.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort((short) encoded.length)
                .put(encoded)
                .array();
    }

    private static byte[] encodeOptionalString(String value) {
        if (value == null) {
            return new byte[] {0};
        }
        byte[] encoded = encodeString(value);
        return ByteBuffer.allocate(1 + encoded.length)
                .put((byte) 1)
                .put(encoded)
                .array();
    }

    private static int decodePort(InputStream in) throws IOException {
        return ByteBuffer.wrap(readBytes(in, Short.BYTES))
                .order(ByteOrder.BIG_ENDIAN)
                .getShort() & 0xffff;
    }

    private static String decodeString(InputStream in) throws IOException {
        int length = ByteBuffer.wrap(readBytes(in, Short.BYTES))
                .order(ByteOrder.BIG_ENDIAN)
                .getShort() & 0xffff;
        return new String(readBytes(in, length), StandardCharsets.UTF_8);
    }

    private static String decodeOptionalString(InputStream in) throws IOException {
        int present = readBytes(in, 1)[0] & 0xff;
        if (present == 0) {
            return null;
        }
        if (present != 1) {
            throw new IOException("Invalid optional string marker: " + present);
        }
        return decodeString(in);
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int bytesRead = in.read(bytes, offset, length - offset);
            if (bytesRead == -1) {
                throw new IOException("End of stream reached");
            }
            offset += bytesRead;
        }
        return bytes;
    }

}
