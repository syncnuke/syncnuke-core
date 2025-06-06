package io.github.syncnuke.client.internal.syncplay.service;

import com.google.protobuf.Message;
import io.github.syncnuke.client.internal.syncplay.data.Command;
import io.github.syncnuke.client.internal.syncplay.data.CommandMessage;
import lombok.extern.slf4j.Slf4j;
import pl.syncplay.proto.SyncplayProto;

import java.lang.reflect.Method;
import java.util.Optional;

@Slf4j
public final class DataProcessor {

    /** Return the (command,message) pair or {@code Optional.empty()} if unknown. */
    public Optional<CommandMessage> get(SyncplayProto.SyncplayMessage envelope) {

        SyncplayProto.SyncplayMessage.BodyCase bc = envelope.getBodyCase();
        if (bc == SyncplayProto.SyncplayMessage.BodyCase.BODY_NOT_SET) {
            return Optional.empty();
        }

        // "HELLO" -> "Hello", etc.
        String key = bc.name().charAt(0) + bc.name().substring(1).toLowerCase();

        for (Command cmd : Command.values()) {
            if (cmd.getName().equals(key)) {
                try {
                    // invoke envelope.getHello(), getState(), getSet(), …
                    Method getter = envelope.getClass().getMethod("get" + key);
                    Message inner = (Message) getter.invoke(envelope);
                    return Optional.of(new CommandMessage(cmd, inner));
                } catch (ReflectiveOperationException e) {
                    log.error("Failed to extract {}", key, e);
                    return Optional.empty();
                }
            }
        }

        log.warn("No Command enum constant for top-level key '{}'", key);
        return Optional.empty();
    }

}
