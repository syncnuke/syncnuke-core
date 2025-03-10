package syncnuke.tcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.commands.Command;
import syncnuke.syncplay.data.BaseData;

@Slf4j
public class DataProcessor {

    private static final ObjectMapper mapper = new ObjectMapper();

    public BaseData get(String json) {
        try {
            JsonNode jsonNode = mapper.readTree(json);

            for (Command command : Command.values()) {
                if (jsonNode.has(command.getName())) {
                    return getObject(jsonNode, command.getName(), command.getDataClass());
                }
            }

        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
        }

        throw new RuntimeException("No command found in JSON: " + json);
    }

    private BaseData getObject(JsonNode jsonNode, String fieldName, Class<? extends BaseData> dataClass) throws JsonProcessingException {
        if (!jsonNode.has(fieldName)) {
            throw new IllegalArgumentException("JSON does not contain field name: " + fieldName);
        }
        return mapper.treeToValue(jsonNode.get(fieldName), dataClass);
    }
}
