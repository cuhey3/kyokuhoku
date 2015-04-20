package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.Memory;
import com.mycode.kyokuhoku.Settings;
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
        String insert = "INSERT INTO seiyu (name, ameblo_url, ameblo_last_img, ameblo_last_modified, ameblo_last_crawl, ameblo_title, ameblo_raw_img_url) SELECT :#${body[name]}, :#${body[ameblo_url]}, :#${body[ameblo_last_img]}, :#${body[ameblo_last_modified]}, :#${body[ameblo_last_crawl]}, :#${body[ameblo_title]}, :#${body[ameblo_raw_img_url]}";
        String update = "UPDATE seiyu SET ameblo_url=:#${body[ameblo_url]},ameblo_last_img=:#${body[ameblo_last_img]} ,ameblo_last_modified=:#${body[ameblo_last_modified]},ameblo_last_crawl=:#${body[ameblo_last_crawl]},ameblo_title=:#${body[ameblo_title]},ameblo_raw_img_url=:#${body[ameblo_raw_img_url]} WHERE name=:#${body[name]}";

        from("direct:ameblo_antenna.memory")
                .to("sql:select ameblo_last_img, name, ameblo_title, ameblo_url from seiyu where ameblo_last_img is not null and ameblo_ignore is null and seiyu_ignore is null order by ameblo_last_modified desc?dataSource=ds")
                .process(Memory.save("ameblo_antenna.query"));

        from("direct:ameblo_antenna.init")
                .choice().when(Memory.hit("ameblo_antenna.query"))
                .otherwise().to("direct:ameblo_antenna.memory")
                .end()
                .to("direct:websocket.setSend");

        from("direct:ameblo_antenna.newImage")
                .to("direct:utility.mapToHeader")
                .process(Utility.GetBytesProcessor(simple("${header.ameblo_raw_img_url}"), simple("${header.ameblo_last_img}")))
                .toF("file:%s/ameblo_antenna/", Settings.PUBLIC_RESOURCE_PATH)
                .process(new AmebloNotifyNewImageInfoProcessor())
                .to("direct:websocket.setSendToAll").to("direct:send/ameblo_antenna");

        from("timer:ameblo_antenna.checkOne?period=15s").routeId("ameblo_antenna.checkOne")
                .to("sql:select * from seiyu where koepota_exist = true and ameblo_url is not null and ameblo_ignore is null and seiyu_ignore is null order by (ameblo_last_crawl is not null), ameblo_last_crawl limit 1?dataSource=ds")
                .setBody(simple("${body[0]}"))
                .process(new AmebloGetLatestImageInfoProcessor())
                .toF("sql:WITH upsert AS (%s RETURNING *) %s WHERE NOT EXISTS (SELECT * FROM upsert)?dataSource=ds", update, insert)
                .filter(simple("${body[img_update]}"))
                .to("direct:ameblo_antenna.newImage")
                .to("direct:ameblo_antenna.memory");

        if (!Settings.isLocal) {
            from("timer:ameblo_antenna.fillExistImages?repeatCount=1").routeId("ameblo_antenna.fillExistImages")
                    .to("sql:select * from seiyu where ameblo_last_img is not null and ameblo_raw_img_url is not null and seiyu_ignore is null?dataSource=ds")
                    .split(body(List.class))
                    .process(Utility.GetBytesProcessor(simple("${body[ameblo_raw_img_url]}"), simple("${body[ameblo_last_img]}")))
                    .toF("file:%s/ameblo_antenna/", Settings.PUBLIC_RESOURCE_PATH);
        }
    }

}

class AmebloGetLatestImageInfoProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> map = exchange.getIn().getBody(Map.class);
        map.put("ameblo_last_crawl", System.currentTimeMillis());
        map.put("img_update", false);
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
                    map.put("img_update", true);
                    map.put("ameblo_title", ameblo_title);
                    map.put("ameblo_last_img", ameblo_last_img);
                    map.put("ameblo_raw_img_url", ameblo_raw_img_url);
                    map.put("ameblo_last_modified", System.currentTimeMillis());
                }
            }
        }
    }
}

class AmebloNotifyNewImageInfoProcessor implements Processor {

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
