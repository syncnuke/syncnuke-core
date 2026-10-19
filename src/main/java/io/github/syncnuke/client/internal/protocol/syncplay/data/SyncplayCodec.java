package io.github.syncnuke.client.internal.protocol.syncplay.data;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Message;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import io.github.syncnuke.client.internal.net.Codec;
import pl.syncplay.proto.SyncplayProto.*;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** UTF-8 JSON objects, framed with Syncplay's CRLF delimiter. */
public final class SyncplayCodec implements Codec<SyncplayMessage> {

    // Twisted's LineReceiver uses this limit in the reference implementation.
    private static final int MAX_LINE_BYTES = 16384;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();
    private static final JsonFormat.Printer PRINTER = JsonFormat.printer()
            .omittingInsignificantWhitespace();

    @Override
    public byte[] encode(SyncplayMessage message) throws IOException {
        JsonNode value = toJson(message);
        if (value == null || !value.isObject()) {
            throw new IOException("Syncplay messages must be JSON objects");
        }
        byte[] json = JSON.writeValueAsBytes(value);
        if (json.length > MAX_LINE_BYTES) {
            throw new IOException("Syncplay message exceeds the line limit");
        }
        ByteArrayOutputStream line = new ByteArrayOutputStream(json.length + 2);
        line.write(json, 0, json.length);
        line.write('\r');
        line.write('\n');
        return line.toByteArray();
    }

    @Override
    public SyncplayMessage decode(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int next = in.read();
            if (next == -1) {
                throw new EOFException("Syncplay connection ended before a complete message");
            }
            if (next != '\n') {
                line.write(next);
                if (line.size() > MAX_LINE_BYTES + 1) {
                    throw new IOException("Syncplay message exceeds the line limit");
                }
                continue;
            }
            byte[] bytes = line.toByteArray();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            if (length > MAX_LINE_BYTES) {
                throw new IOException("Syncplay message exceeds the line limit");
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString().trim();
            line.reset();
            if (json.isEmpty()) {
                continue;
            }
            JsonNode message = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            if (!message.isObject()) {
                throw new IOException("Syncplay messages must be JSON objects");
            }
            return fromJson(message);
        }
    }

    public static SyncplayMessage fromJson(JsonNode root) throws IOException {
        if (!root.isObject()) {
            throw new IOException("Expected Syncplay object");
        }
        SyncplayMessage.Builder frame = SyncplayMessage.newBuilder();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            JsonNode value = entry.getValue();
            SyncplayMessage.Builder command = SyncplayMessage.newBuilder();
            switch (entry.getKey()) {
                case "Hello" -> PARSER.merge(value.toString(), command.getHelloBuilder());
                case "State" -> {
                    validateState(value);
                    PARSER.merge(value.toString(), command.getStateBuilder());
                }
                case "TLS" -> PARSER.merge(value.toString(), command.getTlsBuilder());
                case "Error" -> PARSER.merge(value.toString(), command.getErrorBuilder());
                case "Chat" -> {
                    if (value.isObject()) {
                        command.getChatBuilder().setUsername(value.path("username").asText(""))
                                .setMessage(value.path("message").asText(""));
                    } else if (value.isTextual()) {
                        command.getChatBuilder().setMessage(value.textValue());
                    }
                }
                case "Set" -> {
                    if (!value.isObject()) {
                        throw new IOException("Invalid Set");
                    }
                    SetCommand.Builder set = command.getSetBuilder();
                    if (value.has("room")) {
                        PARSER.merge(value.get("room").toString(), set.getRoomBuilder());
                    }
                    if (value.has("user")) {
                        if (!value.get("user").isObject()) {
                            throw new IOException("Invalid users");
                        }
                        for (Map.Entry<String, JsonNode> user : value.get("user").properties()) {
                            UserSetting.Builder settings = UserSetting.newBuilder().setUsername(user.getKey());
                            JsonNode data = user.getValue();
                            if (!data.isObject()) {
                                throw new IOException("Invalid user");
                            }
                            if (data.has("room")) {
                                PARSER.merge(data.get("room").toString(), settings.getRoomBuilder());
                            }
                            if (data.path("file").isObject()) settings.setFile(readFile(data.get("file")));
                            if (data.path("event").has("left")) {
                                settings.setEvent(UserSetting.Event.LEFT);
                            } else if (data.path("event").has("joined")) {
                                settings.setEvent(UserSetting.Event.JOINED);
                            }
                            set.putUser(user.getKey(), settings.build());
                        }
                    }
                    if (value.path("controllerAuth").has("success")) {
                        PARSER.merge(value.get("controllerAuth").toString(), set.getControllerResultBuilder());
                    }
                    Struct.Builder preserved = Struct.newBuilder();
                    PARSER.merge(value.toString(), preserved);
                    set.setExtensions(preserved);
                }
                case "List" -> {
                    ListMessage.Builder list = command.getListBuilder();
                    if (value.isObject()) {
                        for (Map.Entry<String, JsonNode> room : value.properties()) {
                            RoomEntry.Builder users = RoomEntry.newBuilder().setRoomName(room.getKey());
                            if (!room.getValue().isObject()) {
                                throw new IOException("Invalid room listing");
                            }
                            for (Map.Entry<String, JsonNode> user : room.getValue().properties()) {
                                UserEntry.Builder entryUser = UserEntry.newBuilder().setUsername(user.getKey())
                                        .setController(user.getValue().path("controller").asBoolean());
                                if (user.getValue().path("file").isObject()) entryUser.setFile(readFile(user.getValue().get("file")));
                                users.addUsers(entryUser);
                            }
                            list.addRooms(users);
                        }
                    } else if (!value.isNull()) {
                        throw new IOException("Invalid List");
                    }
                }
                default -> {
                    // Extensions must not disturb playback.
                    continue;
                }
            }
            frame.addCommands(command);
        }
        return frame.getCommandsCount() == 1 ? frame.getCommands(0) : frame.build();
    }

    public static JsonNode toJson(SyncplayMessage command) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        if (command.getCommandsCount() > 0) {
            for (SyncplayMessage part : command.getCommandsList()) {
                root.setAll((ObjectNode) toJson(part));
            }
            return root;
        }
        Message body;
        String key;
        switch (command.getBodyCase()) {
            case HELLO -> {
                key = "Hello";
                body = command.getHello();
            }
            case STATE -> {
                key = "State";
                body = command.getState();
            }
            case ERROR -> {
                key = "Error";
                body = command.getError();
            }
            case TLS -> {
                key = "TLS";
                body = command.getTls();
            }
            case CHAT -> {
                key = "Chat";
                body = command.getChat();
            }
            case LIST -> {
                if (command.getList().getRoomsCount() == 0) {
                    return root.putNull("List");
                }
                ObjectNode rooms = root.putObject("List");
                for (RoomEntry room : command.getList().getRoomsList()) {
                    ObjectNode users = rooms.putObject(room.getRoomName());
                    for (UserEntry user : room.getUsersList()) {
                        users.set(user.getUsername(), json(user));
                    }
                }
                return root;
            }
            case SET -> {
                SetCommand set = command.getSet();
                ObjectNode settings = set.hasExtensions() ? (ObjectNode) json(set.getExtensions()) : JSON.createObjectNode();
                if (set.hasRoom()) {
                    settings.set("room", json(set.getRoom()));
                }
                if (!set.getUserMap().isEmpty() && !settings.has("user")) {
                    ObjectNode users = settings.putObject("user");
                    for (Map.Entry<String, UserSetting> user : set.getUserMap().entrySet()) {
                        ObjectNode data = (ObjectNode) json(user.getValue());
                        data.remove("username");
                        data.remove("event");
                        if (user.getValue().getEvent() != UserSetting.Event.NONE) {
                            data.putObject("event").put(user.getValue().getEvent() == UserSetting.Event.LEFT ? "left" : "joined", true);
                        }
                        users.set(user.getKey(), data);
                    }
                }
                return root.set("Set", settings);
            }
            default -> {
                return root;
            }
        }
        return root.set(key, json(body));
    }

    private static JsonNode json(Message message) throws IOException {
        return JSON.readTree(PRINTER.print(message));
    }

    private static FileInfo readFile(JsonNode file) {
        double duration = file.path("duration").asDouble();
        // File size may be a privacy hash; only name/duration are exposed to applications.
        return FileInfo.newBuilder().setName(file.path("name").asText(""))
                .setDuration(Double.isFinite(duration) ? Math.max(0, duration) : 0).build();
    }

    private static void validateState(JsonNode state) throws IOException {
        if (!state.isObject()) {
            throw new IOException("Invalid State");
        }
        JsonNode play = state.path("playstate");
        if (state.has("playstate") && !play.isObject()) {
            throw new IOException("Invalid playstate");
        }
        if (play.has("paused") && !play.get("paused").isBoolean()) {
            throw new IOException("Invalid paused flag");
        }
        if (play.has("position") && (!play.get("position").isNumber()
                || !Double.isFinite(play.get("position").doubleValue()) || play.get("position").doubleValue() < 0)) {
            throw new IOException("Invalid position");
        }
        for (String name : new String[]{"latencyCalculation", "clientLatencyCalculation", "clientRtt", "serverRtt"}) {
            JsonNode number = state.path("ping").get(name);
            if (number != null && (!number.isNumber() || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0)) {
                throw new IOException("Invalid ping");
            }
        }
        for (String name : new String[]{"server", "client"}) {
            JsonNode number = state.path("ignoringOnTheFly").get(name);
            if (number != null && (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 0)) {
                throw new IOException("Invalid ignoring counter");
            }
        }
    }
}
