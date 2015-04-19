package com.mycode.kyokuhoku;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.diff.JsonDiff;
import java.io.IOException;
import java.util.*;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;

public class JsonResource {

    static final JsonResource instance = new JsonResource();
    private final LinkedHashMap<String, String> resources = new LinkedHashMap<>();
    private final LinkedHashMap<String, List> resourceDiff = new LinkedHashMap<>();
    private final ArrayList<LinkedHashMap<String, Object>> stats = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private static boolean ready = false;

    public static JsonResource getInstance() {
        return instance;
    }

    public <T extends Object> T unmarshal(String jsonString, Class<T> type) throws IOException {
        T readValue = mapper.readValue(jsonString, type);
        return readValue;
    }

    public <T extends Object> T get(String resourceName, Class<T> type) throws IOException {
        T readValue = mapper.readValue(resources.get(resourceName), type);
        return readValue;
    }

    public List getDiff(String resourceName) {
        return resourceDiff.get(resourceName);
    }

    public ArrayList<LinkedHashMap<String, Object>> getStats() {
        return stats;
    }

    public void set(String resourceName, String resource) {
        resources.put(resourceName, resource);
    }

    public void setDiff(String resourceName, String resource) throws IOException {
        resourceDiff.put(resourceName, mapper.readValue(resource, List.class));
    }

    public void setStats(String resourceName, Object o) {
        if (o != null) {
            Iterator<LinkedHashMap<String, Object>> iterator = stats.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().get("name").equals(resourceName)) {
                    iterator.remove();
                }
            }
            LinkedHashMap<String, Object> stat = new LinkedHashMap<>();
            stat.put("name", resourceName);
            stat.put("time", o);
            stats.add(stat);
        }
    }

    public void pushDiff(String resourceName, Object o) {
        List get = resourceDiff.get(resourceName);
        get.add(o);
        while (get.size() > 20) {
            get.remove(0);
        }
    }

    public String getDiffString(String resourceName) throws IOException {
        return getJsonString(resourceDiff.get(resourceName));
    }

    public static Predicate isReady() {
        return new Predicate() {

            @Override
            public boolean matches(Exchange exchange) {
                return ready;
            }
        };
    }

    public void ready() {
        ready = true;
    }

    public String getJsonString(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException t) {
            return "";
        }
    }

    public boolean save(String resourceName, Object o, Exchange exchange) throws IOException {
        String jsonString = getJsonString(o);
        String diff = JsonDiff.asJson(mapper.readTree(resources.get(resourceName)), mapper.readTree(jsonString)).toString();
        if (!diff.equals("[]")) {
            List readValue = mapper.readValue(diff, List.class);
            Map map = new LinkedHashMap<>();
            map.put("time", System.currentTimeMillis());
            map.put("diff", readValue);
            pushDiff(resourceName, map);
            ProducerTemplate pt = exchange.getContext().createProducerTemplate();
            Exchange e = new DefaultExchange(exchange.getContext());
            e.getIn().setHeader("name", resourceName);
            e.getIn().setHeader("json", jsonString);
            e.getIn().setHeader("diff", getDiffString(resourceName));
            long currentTimeMillis = System.currentTimeMillis();
            e.getIn().setHeader("last_update", currentTimeMillis);
            pt.send("seda:json_resource.save", e);
            set(resourceName, jsonString);
            setStats(resourceName, currentTimeMillis);
            return true;
        }
        return false;
    }
}
