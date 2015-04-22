package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.Utility;
import com.mycode.kyokuhoku.WikiUtil;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class AmiamiRoute extends RouteBuilder {

    private final JsonResource jsonResource = JsonResource.getInstance();

    @Override
    public void configure() throws Exception {
        from("timer:amiami.crawl?period=2h").autoStartup(false).routeId("amiami.crawl")
                .process(Utility.getDocumentProcessor(simple("http://www.amiami.jp/top/page/cal/goods.html")))
                .process(jsonResource.load("amiamiTitleToWikiTitle", Map.class))
                .process(jsonResource.load("amiamiItemMap", Map.class))
                .process(new AmiamiGetLatestItemsProcessor())
                .process(jsonResource.save("amiamiTitleToWikiTitle"))
                .process(jsonResource.save("amiamiItemMap"));
    }
}

class AmiamiGetLatestItemsProcessor implements Processor {

    public static final Pattern seriesPattern = Pattern.compile("^『(.+)』シリーズ$");

    @Override
    public void process(Exchange exchange) throws Exception {
        Document doc = exchange.getIn().getBody(Document.class);
        doc.select(".listitem:has(.originaltitle:matches(^$))").remove();
        Elements el = doc.select(".listitem");
        Map<String, String> amiamiTitleToWikiTitle = exchange.getIn().getHeader("amiamiTitleToWikiTitle", Map.class);
        Map<String, ArrayList<Map<String, String>>> amiamiItemMap = exchange.getIn().getHeader("amiamiItemMap", Map.class);
        for (Element e : el) {
            String title = e.select(".originaltitle").text();
            String wikiTitle;
            if (amiamiTitleToWikiTitle.containsKey(title)) {
                wikiTitle = amiamiTitleToWikiTitle.get(title);
            } else {
                wikiTitle = WikiUtil.getWikiTitle(seriesPattern.matcher(title).replaceFirst("$1"));
                amiamiTitleToWikiTitle.put(title, wikiTitle);
            }
            if (wikiTitle != null) {
                ArrayList<Map<String, String>> items;
                String link = e.select(".name a").attr("href");
                if (amiamiItemMap.containsKey(wikiTitle)) {
                    items = amiamiItemMap.get(wikiTitle);
                    Iterator<Map<String, String>> iterator = items.iterator();
                    while (iterator.hasNext()) {
                        Map<String, String> next = iterator.next();
                        if (next.get("link").equals(link)) {
                            iterator.remove();
                        }
                    }
                } else {
                    items = new ArrayList<>();
                }
                Map map = new LinkedHashMap<>();
                map.put("img", e.select("img").attr("src").replace("thumbnail", "main"));
                map.put("link", link);
                map.put("name", e.select("ul li").text());
                map.put("release", e.select(".releasedatetext").text());
                map.put("price", e.select(".price").text());
                map.put("orig", wikiTitle);
                items.add(map);
                amiamiItemMap.put(wikiTitle, items);
            }
        }
        exchange.getIn().setHeader("amiamiTitleToWikiTitle", amiamiTitleToWikiTitle);
        exchange.getIn().setHeader("amiamiItemMap", amiamiItemMap);
    }
}
