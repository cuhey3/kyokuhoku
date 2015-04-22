package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.Settings;
import com.mycode.kyokuhoku.Utility;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

public class AotagaiRoute extends RouteBuilder {
    
    final JsonResource jsonResource = JsonResource.getInstance();
    
    @Override
    public void configure() throws Exception {
        from("direct:aotagai.query")
                .to("sql:select * from aotagai100?dataSource=ds")
                .process(Utility.listToMapByUniqueKey("name"));
        
        from("seda:aotagai.writefile")
                .to("direct:utility.marshal.json")
                .setBody(simple("var aotagai = ${body};"))
                .setHeader(Exchange.FILE_NAME, simple("aotagai.js"))
                .toF("file:%s/aotagai", Settings.PUBLIC_RESOURCE_PATH)
                .to("log:aotagai.writefile.end?showBody=false");
        
        from("timer:aotagai.startup?repeatCount=1").autoStartup(false).routeId("aotagai.startup")
                .to("direct:aotagai.query")
                .to("seda:aotagai.writefile");
        
        from("seda:aotagai.update")
                .to("direct:aotagai.query")
                .filter(jsonResource.saveWithCheck("aotagai100", simple("${body}")))
                .to("seda:aotagai.writefile");
    }
}
