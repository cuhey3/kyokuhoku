package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.Utility;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class KoepotaRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        String insert = "INSERT INTO events (id, day, title, link, hall1, hall2, member) SELECT :#${body[id]}, :#${body[day]}, :#${body[title]}, :#${body[link]}, :#${body[hall1]}, :#${body[hall2]}, :#${body[member]}";
        String update = "UPDATE events SET day=:#${body[day]},title=:#${body[title]} ,link=:#${body[link]},hall1=:#${body[hall1]},hall2=:#${body[hall2]},member=:#${body[member]} WHERE id=:#${body[id]}";

        from("direct:koepota.existUpdateSeiyu")
                .process(new KoepotaExistUpdateSeiyuProcessor()).to("jdbc:ds")
                .to("seda:aotagai.update")
                .to("seda:seiyu.twitter");

        from("direct:koepota.getDocument")
                .process(Utility.GetDocumentProcessor(simple("http://www.koepota.jp/eventschedule/")));

        from("direct:koepota.upsertEvents")
                .filter(header("koepotaEventsUpdate"))
                .to("seda:koepota.done")
                .split(body(List.class))
                .toF("sql:WITH upsert AS (%s RETURNING *) %s WHERE NOT EXISTS (SELECT * FROM upsert)?dataSource=ds", update, insert);

        from("timer:koepota.crawl?period=1h").autoStartup(false).routeId("koepota.crawl")
                .to("direct:koepota.getDocument")
                .process(new KoepotaParseProcessor())
                .to("direct:koepota.upsertEvents")
                .filter(header("koepotaMembersUpdate"))
                .to("direct:koepota.existUpdateSeiyu");

        //from("seda:koepota.done")
        from("timer:foo?repeatCount=1")
                .to("sql:select id from events where done is null?dataSource=ds")
                .process(Utility.listToMapByUniqueKey("id"))
                .setHeader("undone", body())
                .to("direct:koepota.getDocument")
                .process(new Processor() {

                    @Override
                    public void process(Exchange exchange) throws Exception {
                        List undone = exchange.getIn().getHeader("undone", List.class);
                        Document doc = exchange.getIn().getBody(Document.class);
                        Elements select = doc.select("#eventschedule tr");
                        select.remove(0);
                        final Pattern linkToIdPattern = Pattern.compile("^http://www\\.koepota\\.jp/eventschedule/(.+?)\\.html$");
                        for (Element e : select) {
                            Element link_el = e.select("td.title a[href]").first();
                            String link = link_el.attr("href");
                            String id = linkToIdPattern.matcher(link).replaceFirst("$1");
                            undone.remove(id);
                        }
                        exchange.getIn().setBody(undone);
                    }
                }).split(body(List.class))
                .to("sql: update events set done=true where id=:#${body}?dataSource=ds");
    }
}

class KoepotaParseProcessor implements Processor {

    private final Pattern memberParenthesesPattern = Pattern.compile("[\\(（]([^\\(\\)（）]+)[\\)）]");
    private final Pattern linkToIdPattern = Pattern.compile("^http://www\\.koepota\\.jp/eventschedule/(.+?)\\.html$");

    @Override
    public void process(Exchange exchange) throws Exception {
        Document doc = exchange.getIn().getBody(Document.class);
        Elements select = doc.select("#eventschedule tr");
        select.remove(0);
        LinkedHashMap<String, String> members = new LinkedHashMap<>();
        LinkedHashMap<String, Map> result = new LinkedHashMap<>();
        ArrayList<Map<String, String>> resultList = new ArrayList<>();
        for (Element e : select) {
            Map<String, String> row = new LinkedHashMap<>();
            Element link_el = e.select("td.title a[href]").first();
            String day = e.select("td.day").first().text();
            String link = link_el.attr("href");
            String id = linkToIdPattern.matcher(link).replaceFirst("$1");
            String title = link_el.text();
            String hall1 = e.select("td.hall").first().ownText();
            String hall2 = e.select("td.hall .hall").first().text();
            String member = e.select("td.number").text();
            String sellDay = e.select("td.day").eq(1).text();
            row.put("day", day);
            row.put("link", link);
            row.put("id", id);
            row.put("title", title);
            row.put("hall1", hall1);
            row.put("hall2", hall2);
            row.put("member", member);
            ArrayList<String> extractMember = extractMember(member);
            for (String s : extractMember) {
                members.put(s, "");
            }
            row.put("sellday", sellDay);
            result.put(id, row);
            resultList.add(row);
        }
        JsonResource instance = JsonResource.getInstance();
        exchange.getIn().setHeader("koepotaEventsUpdate", instance.save("koepotaEvents", result, exchange));
        exchange.getIn().setHeader("koepotaMembersUpdate", instance.save("koepotaMembers", members, exchange));
        exchange.getIn().setBody(resultList);
    }

    private ArrayList<String> extractMember(String member) {
        Matcher m = memberParenthesesPattern.matcher(member);
        ArrayList<String> members = new ArrayList<>();
        while (m.find()) {
            String parentheses = m.group(1);
            if (parentheses.contains("、")) {
                members.addAll(Arrays.asList(parentheses.split("、")));
            }
            member = m.replaceFirst("");
            m = memberParenthesesPattern.matcher(member);
        }
        members.addAll(Arrays.asList(member.split("、")));
        return members;
    }
}

class KoepotaExistUpdateSeiyuProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        JsonResource instance = JsonResource.getInstance();
        Map<String, String> seiyuName = instance.get("seiyuName", Map.class);
        Map<String, String> koepotaMembers = instance.get("koepotaMembers", Map.class);
        StringBuilder sb = new StringBuilder("'test'");
        for (String name : seiyuName.keySet()) {
            if (name.contains("'")) {
                continue;
            }
            if (koepotaMembers.containsKey(name.replaceFirst(" \\(.+\\)$", ""))) {
                sb.append(",'").append(name).append("'");
            }
        }
        String string = new String(sb);
        String query = "update seiyu set koepota_exist_now = case when name in (%s) then true else false end, koepota_exist = case when name in (%s) then true else koepota_exist end";
        exchange.getIn().setBody(String.format(query, string, string));
    }
}
