package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.Memory;
import com.mycode.kyokuhoku.Utility;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

public class AmebloRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        String insert = "INSERT INTO seiyu (name, ameblo_url, ameblo_last_img, ameblo_last_modified, ameblo_last_crawl, ameblo_title, ameblo_raw_img_url) SELECT :#${header.name}, :#${header.ameblo_url}, :#${header.ameblo_last_img}, :#${header.ameblo_last_modified}, :#${header.ameblo_last_crawl}, :#${header.ameblo_title}, :#${header.ameblo_raw_img_url}";
        String upsert = "UPDATE seiyu SET ameblo_url=:#${header.ameblo_url},ameblo_last_img=:#${header.ameblo_last_img} ,ameblo_last_modified=:#${header.ameblo_last_modified},ameblo_last_crawl=:#${header.ameblo_last_crawl},ameblo_title=:#${header.ameblo_title},ameblo_raw_img_url=:#${header.ameblo_raw_img_url} WHERE name=:#${header.name}";
        from("timer:ameblo_antenna.crawl?period=10s")
                .to("sql:select * from seiyu where koepota_exist = true and ameblo_url is not null and ameblo_ignore is null order by (ameblo_last_crawl is not null), ameblo_last_crawl limit 1?dataSource=ds")
                .setBody(simple("${body[0]}"))
                .process(new AmebloImageUrlProcessor())
                .toF("sql:WITH upsert AS (%s RETURNING *) %s WHERE NOT EXISTS (SELECT * FROM upsert)?dataSource=ds", upsert, insert)
                .filter(header("img_update"))
                .process(new AmebloImageBinaryProcessor())
                .to("file:public/ameblo_antenna/")
                .process(new AmebloNewImageDataProcessor())
                .to("direct:websocket.setSendToAll").to("direct:send/ameblo_antenna")
                .to("direct:ameblo_antenna.query");
        from("timer:ameblo_antenna.img?repeatCount=1").to("sql:select * from seiyu where ameblo_last_img is not null and ameblo_raw_img_url is not null?dataSource=ds")
                .split(body(List.class)).to("direct:utility.mapToHeader")
                .process(new AmebloImageBinaryProcessor()).to("file:public/ameblo_antenna/");
        from("direct:ameblo_antenna.init")
                .choice().when(Memory.hit("ameblo_antenna.query"))
                .otherwise()
                .to("direct:ameblo_antenna.query")
                .end()
                .to("direct:websocket.setSend");
        from("direct:ameblo_antenna.query")
                .to("sql:select ameblo_last_img, name, ameblo_title, ameblo_url from seiyu where ameblo_last_img is not null and ameblo_ignore is null order by ameblo_last_modified desc?dataSource=ds")
                .process(Memory.save("ameblo_antenna.query"));
    }

}

class AmebloImageUrlProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> map = exchange.getIn().getBody(Map.class);
        Utility.mapToHeader(exchange, map, true);
        exchange.getIn().setHeader("ameblo_last_crawl", System.currentTimeMillis());
        exchange.getIn().setHeader("img_update", false);
        Document doc = Utility.getDocument(((String) map.get("ameblo_url")).replaceFirst("/$", "") + "/imagelist.html");
        if (doc != null) {
            Elements el = doc.select(".imgBox .thumBox");
            String ameblo_last_img, ameblo_raw_img_url, ameblo_title;
            if (el != null && !el.isEmpty()) {
                ameblo_title = el.get(0).select(".titLink").text();
                ameblo_raw_img_url = "http://stat" + el.get(0).select("img").attr("src").split("stat001")[1];
                ameblo_last_img = ameblo_raw_img_url.replaceFirst("^(.+?)([^/]+)$", "$2");
                String ameblo_prev_img = (String) map.get("ameblo_last_img");
                if (ameblo_last_img != null && !ameblo_last_img.equals(ameblo_prev_img)) {
                    exchange.getIn().setHeader("img_update", true);
                    exchange.getIn().setHeader("ameblo_title", ameblo_title);
                    exchange.getIn().setHeader("ameblo_last_img", ameblo_last_img);
                    exchange.getIn().setHeader("ameblo_raw_img_url", ameblo_raw_img_url);
                    exchange.getIn().setHeader("ameblo_last_modified", System.currentTimeMillis());
                }
            }
        }
    }
}

class AmebloImageBinaryProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        String url = exchange.getIn().getHeader("ameblo_raw_img_url", String.class);
        exchange.getIn().setBody(Utility.getBytes(url));
        exchange.getIn().setHeader(Exchange.FILE_NAME, exchange.getIn().getHeader("ameblo_last_img", String.class));
    }
}

class AmebloNewImageDataProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();
        Map map = new LinkedHashMap();
        map.put("name", in.getHeader("name"));
        map.put("ameblo_url", in.getHeader("ameblo_url"));
        map.put("ameblo_last_img", in.getHeader("ameblo_last_img"));
        map.put("ameblo_title", in.getHeader("ameblo_title"));
        in.setBody(map);
    }
}
