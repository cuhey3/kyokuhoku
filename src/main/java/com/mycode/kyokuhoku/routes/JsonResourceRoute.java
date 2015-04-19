package com.mycode.kyokuhoku.routes;

import com.mycode.kyokuhoku.JsonResource;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

public class JsonResourceRoute extends RouteBuilder {
    
    @Override
    public void configure() throws Exception {
        from("timer:foo?repeatCount=1").to("sql:select * from resource?dataSource=ds").process(new Processor() {
            
            @Override
            public void process(Exchange exchange) throws Exception {
                List<Map<String, String>> listMap = exchange.getIn().getBody(List.class);
                JsonResource instance = JsonResource.getInstance();
                for (Map<String, String> map : listMap) {
                    String resourceName = map.get("name");
                    instance.set(resourceName, map.get("json"));
                    instance.setDiff(resourceName, map.get("diff"));
                    instance.setStats(resourceName, map.get("last_update"));
                }
                instance.ready();
            }
        }).to("direct:utility.startAllRoutes");
        from("direct:json_resource.search").process(new Processor() {
            
            @Override
            public void process(Exchange exchange) throws Exception {
                Map<String, String> body = exchange.getIn().getBody(Map.class);
                exchange.getIn().setBody(JsonResource.getInstance().getDiff(body.get("name")));
            }
        }).to("direct:websocket.setSend");
        from("direct:json_resource.init").process(new Processor() {
            
            @Override
            public void process(Exchange exchange) throws Exception {
                exchange.getIn().setBody(JsonResource.getInstance().getStats());
            }
        }).to("direct:websocket.setSend");
        from("seda:json_resource.save").to("sql:update resource set json=:#${header.json}, diff=:#${header.diff} ,last_update =:#${header.last_update} where name=:#${header.name}?dataSource=ds");
        from("seda:json_resource.save.seiyu.to_links").to("sql:update seiyu set to_links=:#${header.json}, to_links_diff=:#${header.diff}, to_links_update=:#${header.update} where name=:#${header.id}?dataSource=ds");
    }
}
