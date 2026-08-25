package io.github.syncnuke.client.internal.protocol.syncplay.data;

import com.google.protobuf.Message;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public final class CommandMessage {
    private final Command command;
    private final Message message;
}
