package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum Command {

    JOIN_ROOM((byte) 0x1),
    CONNECT((byte) 0x2),
    UPDATE_STATE((byte) 0x3),
    ;

    private final byte code;

    public static Command fromCode(byte code) {
        for (Command command : values()) {
            if (command.getCode() == code) {
                return command;
            }
        }
        throw new IllegalArgumentException("Unknown command code: " + code);
    }

}
