package com.mycode.kyokuhoku;

import java.util.LinkedHashMap;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;

public class Memory {

    static final Memory instance = new Memory();
    final LinkedHashMap<String, Object> memory = new LinkedHashMap<>();
    final LinkedHashMap<String, Long> createTime = new LinkedHashMap<>();

    public static Memory getInstance() {
        return instance;
    }

    public Object get(String key) {
        Object value = memory.get(key);
        Long time = createTime.get(key);
        if (value != null && System.currentTimeMillis() - time < 60 * 60 * 1000) {
            return value;
        }
        return null;
    }

    private void put(String key, Object value) {
        memory.put(key, value);
        createTime.put(key, System.currentTimeMillis());
    }

    public static Predicate hit(final String scope, final Expression exp) {
        return new Predicate() {

            @Override
            public boolean matches(Exchange exchange) {
                String eval = exp.evaluate(exchange, String.class);
                String key = scope;
                if (eval != null && !eval.isEmpty()) {
                    key += "." + eval;
                }
                Object value = Memory.getInstance().get(key);
                if (value == null) {
                    return false;
                } else {
                    exchange.getIn().setBody(value);
                    System.out.println("returning from memory: " + key);
                    return true;
                }
            }
        };
    }

    public static Predicate hit(final String key) {
        return new Predicate() {

            @Override
            public boolean matches(Exchange exchange) {
                Object value = Memory.getInstance().get(key);
                if (value == null) {
                    return false;
                } else {
                    exchange.getIn().setBody(value);
                    System.out.println("returning from memory: " + key);
                    return true;
                }
            }
        };
    }

    public static Processor save(final String scope, final Expression exp) {
        return new Processor() {

            @Override
            public void process(Exchange exchange) throws Exception {
                String eval = exp.evaluate(exchange, String.class);
                String key = scope;
                if (eval != null && !eval.isEmpty()) {
                    key += "." + eval;
                }
                Object body = exchange.getIn().getBody();
                Memory.getInstance().put(key, body);
                System.out.println("updating memory: " + key);
            }
        };
    }

    public static Processor save(final String key) {
        return new Processor() {

            @Override
            public void process(Exchange exchange) throws Exception {
                Object body = exchange.getIn().getBody();
                Memory.getInstance().put(key, body);
                System.out.println("updating memory: " + key);
            }
        };
    }
}
