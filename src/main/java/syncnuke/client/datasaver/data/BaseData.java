package syncnuke.client.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BaseData {
    private Command command;
    private State state;
    private double position;

    // TODO: Make this happen by default by implementing Serializable
    public byte[] toBytes() {
        byte[] bytes = new byte[2 + Double.BYTES];
        bytes[0] = command.getCode();
        bytes[1] = state.getCode();
        insertDouble(bytes, 2, position);
        return bytes;
    }

    public static BaseData fromBytes(byte[] bytes) {
        if (bytes.length < 3) {
            throw new IllegalArgumentException("Invalid byte array length");
        }

        Command command = Command.fromCode(bytes[0]);
        State state = State.fromCode(bytes[1]);
        double position = extractDouble(bytes, 2);

        return new BaseData(command, state, position);
    }

    @SuppressWarnings("SameParameterValue")
    private static double extractDouble(byte[] packet, int start) {
        return ByteBuffer.wrap(packet, start, Double.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getDouble();
    }

    @SuppressWarnings("SameParameterValue")
    private void insertDouble(byte[] packet, int start, double value) {
        ByteBuffer.wrap(packet, start, Double.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putDouble(value);
    }

}
