package com.mycode.kyokuhoku.routes;

import static com.mycode.kyokuhoku.Settings.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.websocket.WebsocketConstants;

public class WebSocketRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        onException(org.apache.camel.component.direct.DirectConsumerNotAvailableException.class).handled(true);
        fromF("websocket://0.0.0.0/%s/?port=%s", PUBLIC_RESOURCE_PATH, PORT)
                .toF("websocket://0.0.0.0/%s/?staticResources=file:%s&port=%s", PUBLIC_RESOURCE_PATH, PUBLIC_RESOURCE_PATH, PORT);
        fromF("file:%s?noop=true&include=index.html&recursive=true", PUBLIC_RESOURCE_PATH).autoStartup(false)
                .to("direct:websocket.make");
        from("direct:websocket.setSend").setHeader("send", constant(true));
        from("direct:websocket.setSendToAll").setHeader("send", constant(true)).setHeader(WebsocketConstants.SEND_TO_ALL, constant(true));
        from("direct:websocket.make").process(new Processor() {

            @Override
            public void process(Exchange exchange) throws Exception {
                String path = exchange.getIn().getHeader(Exchange.FILE_PARENT, String.class).replaceFirst("^" + PUBLIC_RESOURCE_PATH, "");
                final String WEBSOCKET_PATH;
                if (path.length() == 0) {
                    WEBSOCKET_PATH = "";
                } else {
                    WEBSOCKET_PATH = "/" + path.substring(1);
                }

                exchange.getContext().addRoutes(new RouteBuilder() {

                    @Override
                    public void configure() throws Exception {
                        fromF("websocket://0.0.0.0%s/ws/?port=%s", WEBSOCKET_PATH, PORT)
                                .setHeader("websocket_path", constant(WEBSOCKET_PATH))
                                .to("direct:dynamic.parseMessage")
                                .choice().when(header("send")).toF("direct:send%s", WEBSOCKET_PATH);
                        fromF("direct:send%s", WEBSOCKET_PATH)
                                .to("direct:utility.marshal.json")
                                .toF("websocket://0.0.0.0%s/ws/?port=%s", WEBSOCKET_PATH, PORT);
                        fromF("seda:message%s", WEBSOCKET_PATH)
                                .process(new WebSocketMessageProcessor())
                                .toF("direct:send%s", WEBSOCKET_PATH);
                    }
                });
            }
        });
    }
}

class WebSocketMessageProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("message", exchange.getIn().getHeader("message"));
        exchange.getIn().setBody(message);
    }
}
