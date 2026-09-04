package io.github.syncnuke.client.internal.protocol.datasaver.data;

import io.github.syncnuke.client.internal.net.Codec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class BaseCodec implements Codec<BaseData> {

    private static final short DOUBLE_BYTES = 8; // Double.BYTES
    private static final short MESSAGE_BYTES = 2 + 2 * DOUBLE_BYTES;

    @Override
    public byte[] encode(BaseData value) {
        if (value == null) {
            return new byte[0];
        }
        return switch (value.getCommand()) {
            case UPDATE_STATE -> encodeUpdateState((StateData) value);
            case JOIN_ROOM -> encodeJoinRoom((JoinData) value);
            case CONNECT -> throw new IllegalArgumentException("CONNECT is a server-only command");
        };
    }

    private byte[] encodeUpdateState(StateData value) {
        byte[] bytes = new byte[MESSAGE_BYTES];
        bytes[0] = value.getCommand().getCode();
        bytes[1] = value.getState().getCode();
        insertDouble(bytes, 2, value.getPosition());
        insertDouble(bytes, 2 + DOUBLE_BYTES, value.getPlaybackSpeed());
        return bytes;
    }

    private byte[] encodeJoinRoom(JoinData value) {
        byte[] username = value.getUsername().getBytes(StandardCharsets.UTF_8);
        byte[] room = value.getRoom().getBytes(StandardCharsets.UTF_8);
        if (username.length > 0xffff) {
            throw new IllegalArgumentException("Username is too long");
        }
        if (room.length > 0xffff) {
            throw new IllegalArgumentException("Room name is too long");
        }
        return ByteBuffer.allocate(5 + username.length + room.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put(Command.JOIN_ROOM.getCode())
                .putShort((short) username.length)
                .put(username)
                .putShort((short) room.length)
                .put(room)
                .array();
    }

    @Override
    public BaseData decode(InputStream in) throws IOException {
        int code = in.read();
        if (code == -1) {
            throw new IOException("End of stream reached");
        }
        return switch (Command.fromCode((byte) code)) {
            case UPDATE_STATE -> decodeUpdateState(in);
            case JOIN_ROOM -> decodeJoinRoom(in);
            case CONNECT -> decodeConnect(in);
        };
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

    private StateData decodeUpdateState(InputStream in) throws IOException {
        byte[] bytes = readBytes(in, MESSAGE_BYTES - 1);
        State state = State.fromCode(bytes[0]);
        double position = extractDouble(bytes, 1);
        double playbackSpeed = extractDouble(bytes, 1 + DOUBLE_BYTES);
        return new StateData(Command.UPDATE_STATE, state, position, playbackSpeed);
    }

    private JoinData decodeJoinRoom(InputStream in) throws IOException {
        String username = decodeString(in);
        String room = decodeString(in);
        return new JoinData(Command.JOIN_ROOM, username, room);
    }

    private ConnectData decodeConnect(InputStream in) throws IOException {
        String host = decodeString(in);
        int port = ByteBuffer.wrap(readBytes(in, Short.BYTES))
                .order(ByteOrder.BIG_ENDIAN)
                .getShort() & 0xffff;
        return new ConnectData(host, port);
    }

    private static String decodeString(InputStream in) throws IOException {
        int length = ByteBuffer.wrap(readBytes(in, Short.BYTES))
                .order(ByteOrder.BIG_ENDIAN)
                .getShort() & 0xffff;
        return new String(readBytes(in, length), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("SameParameterValue")
    private static void insertDouble(byte[] packet, int start, double value) {
        ByteBuffer.wrap(packet, start, DOUBLE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putDouble(value);
    }

    @SuppressWarnings("SameParameterValue")
    private static double extractDouble(byte[] packet, int start) {
        return ByteBuffer.wrap(packet, start, DOUBLE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getDouble();
    }

}
