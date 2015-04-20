package com.mycode.kyokuhoku;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public class MyJsonUtil {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static <T extends Object> T unmarshal(String jsonString, Class<T> type) throws IOException {
        T readValue = mapper.readValue(jsonString, type);
        return readValue;
    }

    public static String getJsonString(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException t) {
            return "";
        }
    }

    public static ObjectMapper mapper() {
        return mapper;
    }
}
