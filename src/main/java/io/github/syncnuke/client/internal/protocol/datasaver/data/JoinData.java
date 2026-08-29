package io.github.syncnuke.client.internal.protocol.datasaver.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class JoinData implements BaseData {

    private Command command;
    private String room;

}
