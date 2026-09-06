package io.github.syncnuke.client.internal.protocol.master.data;

import lombok.Getter;

@Getter
public enum Command {

    CONNECT((byte) 0x1),
    JOIN((byte) 0x3);

    private final byte code;

    Command(byte code) {
        this.code = code;
    }

    public static Command fromCode(byte code) {
        for (Command command : values()) {
            if (command.code == code) {
                return command;
            }
        }
        throw new IllegalArgumentException("Unknown command code: " + code);
    }

}
