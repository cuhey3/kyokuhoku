package com.mycode.kyokuhoku.routes;

import com.mycode.kyokuhoku.Utility;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import static org.apache.camel.component.websocket.WebsocketConstants.*;

public class DynamicRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        onException(org.apache.camel.component.direct.DirectConsumerNotAvailableException.class).handled(true);
        from("direct:init").setBody(constant("connection initialized."));
        from("direct:dynamic.parseMessage")
                .to("direct:utility.unmarshal.jsonArray")
                .process(new DynamicParseMessageProcessor())
                .routingSlip(simple("direct:${header.method}"));
    }
}

class DynamicParseMessageProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        List req = exchange.getIn().getBody(List.class);
        switch (req.size()) {
            case 3:
                if (req.get(2) instanceof Map) {
                    Map<String, Object> parsedHeader = (Map<String, Object>) req.get(2);
                    parsedHeader.remove("send");
                    parsedHeader.remove(SEND_TO_ALL);
                    Utility.mapToHeader(exchange, parsedHeader, true);
                }
            case 2:
                exchange.getIn().setBody(req.get(1));
            case 1:
                String websocket_path = exchange.getIn().getHeader("websocket_path", String.class);
                String method;
                if (websocket_path.isEmpty()) {
                    method = ((String) req.get(0)).split("\\.")[0];
                } else if (websocket_path.startsWith("/")) {
                    method = websocket_path.substring(1) + "." + req.get(0);
                } else {
                    method = "init";
                }
                exchange.getIn().setHeader("method", method);
                break;
        }
    }
}
