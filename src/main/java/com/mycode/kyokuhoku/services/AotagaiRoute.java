package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.Settings;
import java.util.List;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

public class AotagaiRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        from("timer:aotagai.startup?repeatCount=1").autoStartup(false).to("seda:aotagai.update");
        from("seda:aotagai.update").to("sql:select * from aotagai100?dataSource=ds").process(new Processor() {

            @Override
            public void process(Exchange exchange) throws Exception {
                List body = exchange.getIn().getBody(List.class);
                JsonResource.getInstance().save("aotagai100", body, exchange);
            }
        }).to("seda:aotagai.write");
        from("seda:aotagai.write")
                .to("direct:utility.marshal.json")
                .setBody(simple("var aotagai = ${body};"))
                .setHeader(Exchange.FILE_NAME, simple("aotagai.js"))
                .toF("file:%s/aotagai", Settings.PUBLIC_RESOURCE_PATH);
    }
}
