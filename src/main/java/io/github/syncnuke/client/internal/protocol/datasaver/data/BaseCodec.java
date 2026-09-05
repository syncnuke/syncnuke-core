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
            case PING -> encodePing();
            case UPDATE_STATE -> encodeUpdateState((StateData) value);
            case JOIN_ROOM -> encodeJoinRoom((JoinData) value);
            case LEAVE_ROOM -> encodeLeaveRoom((LeaveData) value);
            case CONNECT -> throw new IllegalArgumentException(
                    value.getCommand() + " is a server-only command"
            );
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
        return encodeRoomCommand(
                Command.JOIN_ROOM,
                value.getUsername(),
                value.getRoom(),
                value.getPassword()
        );
    }

    private byte[] encodeLeaveRoom(LeaveData value) {
        return encodeRoomCommand(
                Command.LEAVE_ROOM,
                value.getUsername(),
                value.getRoom(),
                value.getPassword()
        );
    }

    private byte[] encodePing() {
        return new byte[] { Command.PING.getCode() };
    }

    private byte[] encodeRoomCommand(
            Command command,
            String usernameStr,
            String roomStr,
            String passwordStr
    ) {
        byte[] username = usernameStr.getBytes(StandardCharsets.UTF_8);
        byte[] room = roomStr.getBytes(StandardCharsets.UTF_8);
        if (username.length > 0xffff) {
            throw new IllegalArgumentException("Username is too long");
        }
        if (room.length > 0xffff) {
            throw new IllegalArgumentException("Room name is too long");
        }
        byte[] password = encodeOptionalString(passwordStr);
        return ByteBuffer.allocate(5 + username.length + room.length + password.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put(command.getCode())
                .putShort((short) username.length)
                .put(username)
                .putShort((short) room.length)
                .put(room)
                .put(password)
                .array();
    }

    private static byte[] encodeOptionalString(String value) {
        if (value == null) {
            return new byte[] { 0 };
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 0xffff) {
            throw new IllegalArgumentException("String is too long");
        }
        return ByteBuffer.allocate(3 + encoded.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put((byte) 1)
                .putShort((short) encoded.length)
                .put(encoded)
                .array();
    }

    @Override
    public BaseData decode(InputStream in) throws IOException {
        int code = in.read();
        if (code == -1) {
            throw new IOException("End of stream reached");
        }
        return switch (Command.fromCode((byte) code)) {
            case PING -> null;
            case CONNECT -> decodeConnect(in);
            case UPDATE_STATE -> decodeUpdateState(in);
            case JOIN_ROOM -> decodeJoinRoom(in);
            case LEAVE_ROOM -> decodeLeaveRoom(in);
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
        String ignoredPassword = decodeOptionalString(in);
        return new JoinData(username, room, null);
    }

    private ConnectData decodeConnect(InputStream in) throws IOException {
        String host = decodeString(in);
        int port = ByteBuffer.wrap(readBytes(in, Short.BYTES))
                .order(ByteOrder.BIG_ENDIAN)
                .getShort() & 0xffff;
        return new ConnectData(host, port);
    }

    private LeaveData decodeLeaveRoom(InputStream in) throws IOException {
        String username = decodeString(in);
        String room = decodeString(in);
        String ignoredPassword = decodeOptionalString(in);
        return new LeaveData(username, room, null);
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
