package io.github.syncnuke.client.internal.protocol.syncplay.command;

import java.net.ProtocolException;

public final class ErrorCommand {

    public void handle() throws ProtocolException {
        throw new ProtocolException("Syncplay rejected the session");
    }

}
