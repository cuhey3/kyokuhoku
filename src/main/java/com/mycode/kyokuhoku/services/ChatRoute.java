package com.mycode.kyokuhoku.services;

import static com.mycode.kyokuhoku.services.ChatRoute.logMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

public class ChatRoute extends RouteBuilder {

    static final Map<Long, Map> logMap = new LinkedHashMap<>();

    @Override
    public void configure() throws Exception {

        onException(java.lang.ClassCastException.class).handled(true);
        from("direct:chat.post").to("seda:chat.input").to("direct:websocket.setSendToAll");
        from("seda:chat.input").process(new ChatInputProcessor());
        from("direct:chat.init").process(new ChatInitProcessor()).to("direct:websocket.setSend");
    }
}

class ChatInputProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Map body = exchange.getIn().getBody(Map.class);
        logMap.put(Long.parseLong(body.get("id") + ""), body);
    }
}

class ChatInitProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        exchange.getIn().setBody(logMap);
    }
}
