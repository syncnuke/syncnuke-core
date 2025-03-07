package syncnuke.syncplay.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class BaseData {

    private final ObjectMapper objectMapper = new ObjectMapper();

    BaseData() {
        objectMapper.enable(SerializationFeature.WRAP_ROOT_VALUE);
    }

    public String serialize() throws JsonProcessingException {
        return objectMapper.writeValueAsString(this);
    }

}
