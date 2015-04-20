package com.mycode.kyokuhoku;

import com.github.fge.jsonpatch.diff.JsonDiff;
import java.io.IOException;
import java.util.*;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;

public class JsonResource {

    static final JsonResource instance = new JsonResource();
    private final LinkedHashMap<String, String> resources = new LinkedHashMap<>();
    private final LinkedHashMap<String, List> resourceDiff = new LinkedHashMap<>();
    private final ArrayList<LinkedHashMap<String, Object>> stats = new ArrayList<>();
    private static boolean ready = false;

    public static JsonResource getInstance() {
        return instance;
    }

    public <T extends Object> T get(String resourceName, Class<T> type) throws IOException {
        T readValue = MyJsonUtil.mapper().readValue(resources.get(resourceName), type);
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
        resourceDiff.put(resourceName, MyJsonUtil.mapper().readValue(resource, List.class));
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
        return MyJsonUtil.getJsonString(resourceDiff.get(resourceName));
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

    public boolean save(String resourceName, Object o, Exchange exchange) throws IOException {
        String jsonString = MyJsonUtil.getJsonString(o);
        String diff = JsonDiff.asJson(MyJsonUtil.mapper().readTree(resources.get(resourceName)), MyJsonUtil.mapper().readTree(jsonString)).toString();
        if (!diff.equals("[]")) {
            List readValue = MyJsonUtil.mapper().readValue(diff, List.class);
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

    public Processor save(final String resourceName, final Expression exp) {
        return new Processor() {

            @Override
            public void process(Exchange exchange) {
                try {
                    save(resourceName, exp.evaluate(exchange, Object.class), exchange);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        };
    }

    public Processor save(final String resourceName) {
        return new Processor() {

            @Override
            public void process(Exchange exchange) {
                try {
                    save(resourceName, exchange.getIn().getHeader(resourceName), exchange);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        };
    }

    public Predicate saveWithCheck(final String resourceName, final Expression exp) {
        return new Predicate() {

            @Override
            public boolean matches(Exchange exchange) {
                try {
                    return save(resourceName, exp.evaluate(exchange, Object.class), exchange);
                } catch (Throwable t) {
                    t.printStackTrace();
                    return false;
                }
            }
        };
    }

    public Processor load(final String resourceName, final Class type) {
        return new Processor() {

            @Override
            public void process(Exchange exchange) throws Exception {
                exchange.getIn().setHeader(resourceName, get(resourceName, type));
            }
        };
    }
}
