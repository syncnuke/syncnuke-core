package io.github.syncnuke.client.datasaver.data;


import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum State {

    PAUSED((byte) 0x0),
    PLAYING((byte) 0x1),
    ;

    private final byte code;

    public static State fromCode(byte code) {
        for (State state : values()) {
            if (state.getCode() == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown state code: " + code);
    }

}
