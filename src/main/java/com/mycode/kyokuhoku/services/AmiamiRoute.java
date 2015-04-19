package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.Utility;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
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

    @Override
    public void configure() throws Exception {
        from("timer:amiami.crawl?period=2h").autoStartup(false)
                .process(new AmiamiGetItemsProcessor());
    }
}

class AmiamiGetItemsProcessor implements Processor {

    public static final Pattern seriesPattern = Pattern.compile("^『(.+)』シリーズ$");

    @Override
    public void process(Exchange exchange) throws Exception {
        Document doc = Utility.getDocument("http://www.amiami.jp/top/page/cal/goods.html");
        doc.select(".listitem:has(.originaltitle:matches(^$))").remove();
        Elements el = doc.select(".listitem");
        JsonResource instance = JsonResource.getInstance();
        Map<String, String> amiamiTitleToWikiTitle = instance.get("amiamiTitleToWikiTitle", Map.class);
        Map<String, ArrayList<Map<String, String>>> amiamiItemMap = instance.get("amiamiItemMap", Map.class);
        for (Element e : el) {
            String title = e.select(".originaltitle").text();
            String wikiTitle;
            if (amiamiTitleToWikiTitle.containsKey(title)) {
                wikiTitle = amiamiTitleToWikiTitle.get(title);
            } else {
                wikiTitle = getWikiTitle(title);
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
        instance.save("amiamiTitleToWikiTitle", amiamiTitleToWikiTitle, exchange);
        instance.save("amiamiItemMap", amiamiItemMap, exchange);
    }

    static String getWikiTitle(String title) throws UnsupportedEncodingException {
        title = seriesPattern.matcher(title).replaceFirst("$1");
        Document doc;
        doc = Utility.getDocument("http://ja.wikipedia.org/w/api.php?action=query&format=xml&titles=" + URLEncoder.encode(title, "UTF-8"));
        if (doc != null) {
            if (doc.select("page[pageid]").isEmpty()) {
                doc = Utility.getDocument("http://ja.wikipedia.org/w/api.php?action=query&format=xml&titles=" + URLEncoder.encode(title.replace("！", "!").replace("？", "?").replace("；", ";").replace("’", "'").replace("＆", "&").replace("：", ":").replace("＊", "*").replace("，", ",").replace("＠", "@"), "UTF-8"));
                if (doc.select("page[pageid]").isEmpty()) {
                    return null;
                } else {
                    return doc.select("page[pageid]").attr("title");
                }
            } else {
                return doc.select("page[pageid]").attr("title");
            }
        }
        return null;
    }
}
