package io.github.syncnuke.client.internal.syncplay.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.github.syncnuke.client.internal.syncplay.data.exception.SerializationException;
import io.github.syncnuke.client.internal.tcp.Codec;
import pl.syncplay.proto.SyncplayProto.SyncplayMessage;

import java.io.*;
import java.lang.reflect.InvocationTargetException;

public final class ProtoJsonCodec implements Codec<SyncplayMessage> {

    private static final String LF = "\r\n";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFormat.Printer PRINTER =
            JsonFormat.printer().omittingInsignificantWhitespace();
    private static final JsonFormat.Parser PARSER =
            JsonFormat.parser().ignoringUnknownFields();

    @Override
    public byte[] encode(SyncplayMessage msg) {
        try {
            String jsonKey = capital(msg.getBodyCase().name().toLowerCase());
            Message inner = (Message) SyncplayMessage
                    .class.getMethod("get" + jsonKey)
                    .invoke(msg);

            JsonNode wrapper = MAPPER.createObjectNode()
                    .set(jsonKey, MAPPER.readTree(PRINTER.print(inner)));

            return (MAPPER.writeValueAsString(wrapper) + LF).getBytes();
        } catch (InvalidProtocolBufferException | JsonProcessingException | InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            throw new SerializationException(e);
        }
    }

    @Override
    public SyncplayMessage decode(InputStream in) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        String line = br.readLine();                 // one message == one line
        if (line == null) throw new EOFException();

        JsonNode root = MAPPER.readTree(line);
        String key = root.fieldNames().next();    // "Hello", "State", …

        SyncplayMessage.Builder envelope = SyncplayMessage.newBuilder();
        // Dynamically resolve the builder method based on the key
        String builderMethodName = "get" + key + "Builder";
        try {
            com.google.protobuf.Message.Builder builder =
                    (com.google.protobuf.Message.Builder) SyncplayMessage.Builder.class
                            .getMethod(builderMethodName)
                            .invoke(envelope);

            PARSER.merge(root.get(key).toString(), builder);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Unknown root: " + key, e);
        }
        return envelope.build();
    }

    private static String capital(String s) {
        return s.substring(0,1).toUpperCase() + s.substring(1);
    }

}
