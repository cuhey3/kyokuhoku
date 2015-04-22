package com.mycode.kyokuhoku.routes;

import com.mycode.kyokuhoku.MTRandom;
import com.mycode.kyokuhoku.Utility;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;

public class UtilityRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        onException(com.fasterxml.jackson.core.JsonParseException.class).handled(true);
        onException(java.lang.ClassCastException.class).handled(true);
        from("direct:utility.unmarshal.jsonObject").unmarshal().json(JsonLibrary.Jackson, Map.class);
        from("direct:utility.unmarshal.jsonArray").unmarshal().json(JsonLibrary.Jackson, List.class);
        from("direct:utility.marshal.json").marshal().json(JsonLibrary.Jackson).setBody(body(String.class).append(""));
        from("timer:knock?period=30m").process(new UtilityKnockerProcessor());
        from("direct:utility.mapBodyToHeader").process(new UtilityMapBodyToHeaderProcessor());
        from("direct:utility.getRandom").process(new UtilityGetRandomProcessor());
        from("direct:utility.bodyToMessage").process(new UtilityBodyToMessageProcessor());
        from("direct:utility.startAllRoutes").process(new UtilityStartAllRoutesProcessor());
    }
}

class UtilityKnockerProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Utility.getDocument("http://myknocker.herokuapp.com/");
    }
}

class UtilityMapBodyToHeaderProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Utility.mapBodyToHeader(exchange, exchange.getIn().getBody(Map.class), false);
    }
}

class UtilityGetRandomProcessor implements Processor {

    final MTRandom random = new MTRandom();

    @Override
    public void process(Exchange exchange) throws Exception {
        Integer random_max = exchange.getIn().getHeader("random_max", Integer.class);
        exchange.getIn().setHeader("random", random.nextInt(random_max) + 1);
    }
}

class UtilityBodyToMessageProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Map map = new LinkedHashMap();
        map.put("message", exchange.getIn().getBody(String.class));
        exchange.getIn().setBody(map);
    }
}

class UtilityStartAllRoutesProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        exchange.getContext().startAllRoutes();
    }
}
