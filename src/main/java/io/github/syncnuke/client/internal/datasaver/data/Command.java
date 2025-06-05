package io.github.syncnuke.client.internal.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum Command {

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
