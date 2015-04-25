package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonColumnUtil;
import com.mycode.kyokuhoku.MyJsonUtil;
import com.mycode.kyokuhoku.Utility;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.jsoup.nodes.Document;

public class SeiyuWikiParseRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        from("direct:seiyu_wiki_parse.parse")
                .setHeader("page", simple("${body[page]}"))
                .process(Utility.urlEncode(simple("${header.page}"), "encodedPage"))
                .process(Utility.getJsonProcessor(simple("http://ja.wikipedia.org/w/api.php?action=parse&page=${header.encodedPage}&prop=wikitext|links&format=json&redirects")))
                .to("direct:utility.unmarshal.jsonObject")
                .process(new SeiyuWikiParseMainProcessor());

        from("direct:seiyu_wiki_parse.charname").process(new SeiyuWikiParseCharNameProcessor());

        from("seda:seiyuwikiparse.getIntro")
                .to("sql:select name from seiyu where exintro is null?dataSource=ds")
                .split(body(List.class))
                .setHeader("name", simple("${body[name]}"))
                .process(Utility.urlEncode(simple("${header.name}"), "encodedName"))
                .process(Utility.getDocumentProcessor(simple("http://ja.wikipedia.org/w/api.php?format=xml&action=query&titles=${header.encodedName}&prop=extracts&exintro&explaintext")))
                .process(new SeiyuWikiGetIntroProcessor())
                .to("sql:update seiyu set pageid =:#${header.pageid}, exintro =:#${header.exintro} where name =:#${header.name}?dataSource=ds")
                .to("log:seiyuwikiparse.getIntro.sql?showHeaders=true");

        from("timer:foofoo?period=1m")
                .to("sql:select * from seiyu where koepota_exist and seiyu_ignore is null order by (to_links_last_crawl is not null),to_links_last_crawl limit 1?dataSource=ds&delay=1m").autoStartup(false)
                .process(new SeiyuToLinksProcessor())
                .to("sql:update seiyu set to_links_last_crawl =:#${header.now}, to_links_count =:#${header.to_links_count}, to_links_exists_count =:#${header.to_links_exists_count} where name=:#${header.name}?dataSource=ds");
    }
}

class SeiyuWikiParseMainProcessor implements Processor {

    Pattern p1 = Pattern.compile("<!--.+?-->", Pattern.DOTALL);
    Pattern p2 = Pattern.compile("<ref.*?(/>|>.*?</ref>)", Pattern.DOTALL);
    Pattern p = Pattern.compile("^([:;]|! |'+|\\*+|=+|\\{\\||\\|-|\\|\\}|\\}+|\\| ?)");
    Pattern year1 = Pattern.compile("^'''\\s*(\\d{4}年|時期未定)\\s*'''\\s*$");
    Pattern year2 = Pattern.compile("^!.*(\\d{4}年|時期未定)\\s*$");

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Map> json = exchange.getIn().getBody(Map.class);
        ArrayList<LinkedHashMap<String, String>> tokens = new ArrayList<>();
        Map jsonCursor = json.get("parse");
        jsonCursor = (Map) jsonCursor.get("wikitext");
        String wikitext = (String) jsonCursor.get("*");
        wikitext = p1.matcher(wikitext).replaceAll("");
        wikitext = p2.matcher(wikitext).replaceAll("");
        StringBuilder buffer = new StringBuilder();
        LinkedHashMap<String, String> section = new LinkedHashMap<>();
        for (String s : wikitext.split("\r\n|\n|\r")) {
            Matcher m = p.matcher(s);
            if (m.find()) {
                switch (m.group(1)) {
                    case "| ":
                    case "|":
                        buffer.append("\n").append(s);
                        break;
                    case "}}":
                        buffer.append("\n").append(s);
                        outputSection(tokens, section, buffer);
                        break;
                    case "'''":
                        Matcher m_year1 = year1.matcher(s);
                        if (m_year1.find()) {
                            outputSection(tokens, section, buffer);
                            buffer = new StringBuilder();
                            section.put("year", m_year1.group(1));
                        } else {
                            buffer = new StringBuilder(s);
                        }
                        break;
                    case "==":
                        section = new LinkedHashMap<>();
                        buffer = new StringBuilder();
                        s = s.replaceFirst("^==\\s*(.+?)\\s*==\\s*$", "$1");
                        section.put("h2", s);
                        section.remove("h3");
                        section.remove("h4");
                        section.remove("h5");
                        section.remove("year");
                        break;
                    case "===":
                        s = s.replaceFirst("^===\\s*(.+?)\\s*===\\s*$", "$1");
                        buffer = new StringBuilder();
                        section.put("h3", s);
                        section.remove("h4");
                        section.remove("year");
                        break;
                    case "====":
                        s = s.replaceFirst("^====\\s*(.+?)\\s*====\\s*$", "$1");
                        buffer = new StringBuilder();
                        section.put("h4", s);
                        section.remove("h5");
                        section.remove("year");
                        break;
                    case ";":
                        s = s.replaceFirst("^;\\s*(.+?)\\s*$", "$1");
                        buffer = new StringBuilder();
                        section.put("h5", s);
                        section.remove("year");
                        break;
                    case "*":
                    case ":":
                        outputSection(tokens, section, buffer);
                        buffer = new StringBuilder(s);
                        break;
                    case "**":
                        buffer.append("\n").append(s);
                        break;
                    case "{|":
                        break;
                    case "!":
                    case "! ":
                        Matcher m_year2 = year2.matcher(s);
                        if (m_year2.find()) {
                            section.put("year", m_year2.group(1));
                        }
                        break;
                    case "|-":
                        outputSection(tokens, section, buffer);
                        buffer = new StringBuilder();
                        break;
                }
            } else {
                if (s.isEmpty() || s.matches("^\\s+$")) {
                    outputSection(tokens, section, buffer);
                    buffer = new StringBuilder();
                } else {
                    if (buffer.length() > 0) {
                        buffer.append("\n").append(s);
                    } else {
                        buffer.append(s);
                    }
                }
            }
        }
        outputSection(tokens, section, buffer);
        jsonCursor = (Map) json.get("parse");
        List<Map<String, Object>> links = (List) jsonCursor.get("links");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        LinkedHashMap<String, Object> linksToToken = new LinkedHashMap<>();
        ArrayList<LinkedHashMap<String, String>> tokenArray = new ArrayList<>();
        for (Map<String, Object> link : links) {
            if ((int) link.get("ns") == 0) {
                String title = (String) link.get("*");
                if (title.length() < 2) {
                    continue;
                }
                ArrayList<Integer> ex = new ArrayList<>();
                for (int i = 0; i < tokenArray.size(); i++) {
                    if (tokenArray.get(i).get("s").contains(title)) {
                        ex.add(i);
                    }
                }
                for (LinkedHashMap<String, String> section2 : tokens) {
                    String s = section2.get("s");
                    if (s.contains(title)) {
                        ex.add(tokenArray.size());
                        tokenArray.add(new LinkedHashMap<>(section2));
                        section2.put("s", "");
                    }
                }
                if (!ex.isEmpty()) {
                    LinkedHashMap<String, Object> r = new LinkedHashMap<>();
                    if (link.containsKey("exists")) {
                        r.put("e", 1);
                    }
                    r.put("t", ex);
                    linksToToken.put(title, r);
                }
            }
        }
        result.put("links", linksToToken);
        result.put("tokens", tokenArray);
        exchange.getIn().setBody(result);
    }

    public static void outputSection(ArrayList<LinkedHashMap<String, String>> tokens, LinkedHashMap<String, String> section, StringBuilder buffer) {
        if (buffer.length() > 0) {
            LinkedHashMap<String, String> section2 = new LinkedHashMap<>(section);
            section2.put("s", new String(buffer));
            section.put("s", new String(buffer));
            tokens.add(section2);
        }
    }
}

class SeiyuWikiParseCharNameProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Map result = exchange.getIn().getBody(Map.class);
        Map<String, Object> links = (Map) result.get("links");
        List<Map> tokens = (List) result.get("tokens");
        Pattern p = Pattern.compile("[\\(（]([^\\(（）\\)]+?)[）\\)][ 　]*$");
        Matcher m;
        LinkedHashMap<String, Set<String>> charNameSetMap = new LinkedHashMap<>();
        for (String key : links.keySet()) {
            List<Integer> list = (List<Integer>) ((Map) links.get(key)).get("t");
            for (Integer i : list) {
                String[] replace = ((String) tokens.get(i).get("s")).split("\r\n|\n|\r");
                for (String s : replace) {
                    if (s.contains(key) && (m = p.matcher(s)).find()) {
                        String group = m.group(1);
                        group = group.replaceAll("'''(.+?)'''", "$1");
                        group = group.replaceAll("\\[\\[(.+?)\\]\\]", "$1");
                        String[] split = group.split("( ?/ ?|、|\\|)");
                        Set<String> get = charNameSetMap.get(key);
                        if (get == null) {
                            get = new HashSet<>();
                        }
                        get.add(group);
                        get.addAll(Arrays.asList(split));
                        charNameSetMap.put(key, get);
                    }
                }
            }
        }
        exchange.getIn().setBody(charNameSetMap);
    }
}

class SeiyuToLinksProcessor implements Processor {

    private final Pattern p1 = Pattern.compile("<!--.+?-->", Pattern.DOTALL);
    private final Pattern p2 = Pattern.compile("<ref.*?(/>|>.*?</ref>)", Pattern.DOTALL);

    private final Pattern date = Pattern.compile("^(([1-9]|1[0-2])月([1-9]|[12][0-9]|3[01])日|(\\d{1,2})?(\\d{2})年)$");
    private final Pattern ngWord = Pattern.compile("^(日本|女性|男性|声優|ABO式血液型|Twitter|センチメートル|俳優|舞台|女優|歌手|シンガーソングライター)$");

    @Override
    public void process(Exchange exchange) throws Exception {
        List list = exchange.getIn().getBody(List.class);
        Map<String, Object> body = (Map) list.get(0);
        String name = (String) body.get("name");
        String jsonString = Utility.getJson("http://ja.wikipedia.org/w/api.php?action=parse&page=" + URLEncoder.encode(name, "UTF-8") + "&prop=wikitext|links&format=json&redirects");

        Map<String, Map> json = MyJsonUtil.unmarshal(jsonString, Map.class);
        Map jsonCursor = json.get("parse");
        jsonCursor = (Map) jsonCursor.get("wikitext");
        String wikitext = (String) jsonCursor.get("*");
        wikitext = p1.matcher(wikitext).replaceAll("");
        wikitext = p2.matcher(wikitext).replaceAll("");
        jsonCursor = (Map) json.get("parse");
        List<Map<String, Object>> links = (List) jsonCursor.get("links");
        LinkedHashMap<String, Boolean> result = new LinkedHashMap<>();
        for (Map<String, Object> link : links) {
            if ((int) link.get("ns") == 0) {
                String title = (String) link.get("*");
                if (title.length() < 2) {
                    continue;
                }
                if (wikitext.contains(title) || wikitext.contains(title.replace(" ", "_"))) {
                    if (!date.matcher(title).find() && !ngWord.matcher(title).find() && link.containsKey("exists")) {
                        result.put(title, true);
                    } else {
                        result.put(title, false);
                    }
                }
            }
        }
        int to_links_count = 0;
        int to_links_exists_count = 0;
        for (String key : result.keySet()) {
            to_links_count++;
            if (result.get(key)) {
                to_links_exists_count++;
            }
        }
        JsonColumnUtil jcu = new JsonColumnUtil(body, JsonColumnUtil.Kind.SEIYU_TOLINKS, exchange);
        jcu.save(result, exchange);
        exchange.getIn().setHeader("name", name);
        exchange.getIn().setHeader("now", System.currentTimeMillis());
        exchange.getIn().setHeader("to_links_count", to_links_count);
        exchange.getIn().setHeader("to_links_exists_count", to_links_exists_count);
    }
}

class SeiyuWikiGetIntroProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Document doc = exchange.getIn().getBody(Document.class);
        try {
            exchange.getIn().setHeader("pageid", doc.select("page[pageid]").first().attr("pageid"));
            exchange.getIn().setHeader("exintro", doc.select("extract").text().replaceFirst("\\^.+", ""));
        } catch (Throwable t) {
            exchange.getIn().setHeader("pageid", null);
            exchange.getIn().setHeader("exintro", "none");
        }
    }
}
