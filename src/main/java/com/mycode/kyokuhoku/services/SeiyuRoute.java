package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.Utility;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class SeiyuRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        String insert = "INSERT INTO seiyu (name, ameblo_url, twitter_url) SELECT :#${header.name}, :#${header.ameblo_url}, :#${header.twitter_url}";
        String upsert = "UPDATE seiyu SET ameblo_url=case when ameblo_url is null then :#${header.ameblo_url} else ameblo_url end, twitter_url=case when twitter_url is null then :#${header.twitter_url} else twitter_url end WHERE name=:#${header.name}";
        from("timer:seiyu.info.crawl?period=24h").autoStartup(false)
                .to("sql:select name from seiyu where seiyu_ignore is null?dataSource=ds")
                .split(body(List.class)).throttle(1).timePeriodMillis(15000)
                .setBody(simple("${body[name]}"))
                .to("direct:seiyu.info.get");
        from("timer:seiyu.categorymembers?period=1h").autoStartup(false)
                .process(new SeiyuCategoryMembersProcessor())
                .filter(header("seiyuNameUpdate"))
                .to("direct:seiyu.new")
                .to("seda:koepota.exist");
        from("direct:seiyu.new")
                .process(new SeiyuNewProcessor())
                .to("direct:seiyu.info.get");
        from("direct:seiyu.info.get")
                .process(new SeiyuGetInfoProcessor())
                .to("direct:seiyu.insert");
        from("direct:seiyu.insert")
                .filter(header("insert"))
                .toF("sql:WITH upsert AS (%s RETURNING *) %s WHERE NOT EXISTS (SELECT * FROM upsert)?dataSource=ds", upsert, insert);
    }
}

class SeiyuCategoryMembersProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        String url = "http://ja.wikipedia.org/w/api.php?action=query&list=categorymembers&cmtitle=Category:%E6%97%A5%E6%9C%AC%E3%81%AE%E5%A5%B3%E6%80%A7%E5%A3%B0%E5%84%AA&cmlimit=500&format=xml&cmnamespace=0&rawcontinue";
        Document doc = Utility.getDocument(url);
        LinkedHashMap<String, String> seiyuName = new LinkedHashMap<>();
        while (true && doc != null) {
            for (Element e : doc.select("categorymembers cm[title]")) {
                seiyuName.put(e.attr("title"), e.attr("pageid"));
            }
            if (doc.select("categorymembers[cmcontinue]").isEmpty()) {
                break;
            } else {
                String cmcontinue = doc.select("categorymembers[cmcontinue]").get(0).attr("cmcontinue");
                doc = Utility.getDocument(url + "&cmcontinue=" + cmcontinue);
            }
        }
        exchange.getIn().setHeader("seiyuNameUpdate", JsonResource.getInstance().save("seiyuName", seiyuName, exchange));
    }
}

class SeiyuGetInfoProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        String name = exchange.getIn().getBody(String.class);
        String url = "http://ja.wikipedia.org/w/api.php?action=parse&prop=externallinks&page=" + URLEncoder.encode(name, "UTF-8") + "&format=xml";
        Document doc = Utility.getDocument(url);
        String ameblo_url = null, twitter_url = null;
        if (doc != null) {
            Elements el = doc.select("externallinks el:matches(^https?://ameblo.jp/[^/]+/?$)");
            if (!el.isEmpty() && !el.text().contains("wakeupgirls")) {
                ameblo_url = getSpecificLink(el, doc);
            }
            el = doc.select("externallinks el:matches(^https?://twitter.com/[^/%@ ]+/?$)");
            twitter_url = getSpecificLink(el, doc);
        }
        JsonResource instance = JsonResource.getInstance();
        Map<String, String> koepotaMembers = instance.get("koepotaMembers", Map.class);
        boolean koepota_exist = koepotaMembers.containsKey(name.replaceFirst(" \\(.+\\)$", ""));
        if (ameblo_url != null || twitter_url != null || koepota_exist) {
            exchange.getIn().setHeader("insert", true);
            exchange.getIn().setHeader("ameblo_url", ameblo_url);
            exchange.getIn().setHeader("twitter_url", twitter_url);
            exchange.getIn().setHeader("name", name);
        }
    }

    public String getSpecificLink(Elements el, Document doc) {
        String specificLink = null;
        if (el.size() == 1) {
            specificLink = el.text();
        } else {
            int max = 0;
            for (Element e : el) {
                String text = e.text();
                int size = doc.select("externallinks el:matches(^" + text + ")").size();
                if (max == size) {
                    max = -1;
                    break;
                } else if (max < size) {
                    specificLink = text;
                    max = size;
                }
            }
            if (max < 1) {
                specificLink = null;
            }
        }
        return specificLink;
    }
}

class SeiyuNewProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        List<Map<String, Object>> updates = JsonResource.getInstance().getDiff("seiyuName");
        Map<String, Object> lastUpdates = updates.get(updates.size() - 1);
        List<Map<String, Object>> updatesDiff = (List<Map<String, Object>>) lastUpdates.get("diff");
        Set<String> set = new LinkedHashSet<>();
        for (Map<String, Object> diff : updatesDiff) {
            if (diff.get("op").equals("add")) {
                String name = (String) diff.get("path");
                name = name.replace("~1", "/");
                set.add(name);
            }
        }
        exchange.getIn().setBody(new ArrayList<>(set));
    }
}
