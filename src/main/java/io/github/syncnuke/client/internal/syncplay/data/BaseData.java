package io.github.syncnuke.client.internal.syncplay.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.syncnuke.client.internal.syncplay.data.exception.SerializationException;

public class BaseData {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.enable(SerializationFeature.WRAP_ROOT_VALUE);
    }

    /**
     * Serializes this object to a JSON string, using the provided view class to determine which fields to include.
     * @return The serialized JSON representation of this object
     */
    public String serialize(Class<?> view) {
        try {
            return objectMapper.writerWithView(view).writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new SerializationException(e);
        }
    }

}
