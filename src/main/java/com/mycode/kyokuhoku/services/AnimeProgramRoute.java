package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.Utility;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class AnimeProgramRoute extends RouteBuilder {

    String insert = "INSERT INTO program (series_title, series_id, program_title, start_time, end_time, program_text, new_flag,repeat_flag,end_flag,station,dayofweek,next_id,program_id,time_exp,logo_url) SELECT :#${header.series_title}, :#${header.series_id}, :#${header.program_title}, :#${header.start_time}, :#${header.end_time}, :#${header.program_text}, :#${header.new_flag}, :#${header.repeat_flag}, :#${header.end_flag}, :#${header.station}, :#${header.dayofweek}, :#${header.next_id}, :#${header.program_id}, :#${header.time_exp}, :#${header.logo_url}";
    String update = "UPDATE program SET series_title=:#${header.series_title}, series_id=:#${header.series_id}, program_title=:#${header.program_title}, start_time=:#${header.start_time}, end_time=:#${header.end_time}, program_text=:#${header.program_text}, new_flag=:#${header.new_flag}, repeat_flag=:#${header.repeat_flag}, end_flag=:#${header.end_flag}, station=:#${header.station}, dayofweek=:#${header.dayofweek}, next_id=:#${header.next_id}, time_exp=:#${header.time_exp}, logo_url=:#${header.logo_url} WHERE program_id=:#${header.program_id}";
    String insert2 = "INSERT INTO program (series_title, series_id, program_title, start_time, end_time, program_text, new_flag,repeat_flag,end_flag,onair_flag,station,dayofweek,next_id,program_id,time_exp,logo_url) SELECT :#${header.series_title}, :#${header.series_id}, :#${header.program_title}, :#${header.start_time}, :#${header.end_time}, :#${header.program_text}, :#${header.new_flag}, :#${header.repeat_flag}, :#${header.end_flag}, :#${header.onair_flag}, :#${header.station}, :#${header.dayofweek}, :#${header.next_id}, :#${header.program_id}, :#${header.time_exp}, :#${header.logo_url}";
    String update2 = "UPDATE program SET series_title=:#${header.series_title}, series_id=:#${header.series_id}, program_title=:#${header.program_title}, start_time=:#${header.start_time}, end_time=:#${header.end_time}, program_text=:#${header.program_text}, new_flag=:#${header.new_flag}, repeat_flag=:#${header.repeat_flag}, end_flag=:#${header.end_flag}, onair_flag=:#${header.onair_flag}, station=:#${header.station}, dayofweek=:#${header.dayofweek}, next_id=:#${header.next_id}, time_exp=:#${header.time_exp}, logo_url=:#${header.logo_url} WHERE program_id=:#${header.program_id}";

    @Override
    public void configure() throws Exception {
        from("quartz2://foo?cron=0+5+4,12,21+*+*+?&trigger.timeZone=Asia/Tokyo").autoStartup(false)
                .process(new AnimeProgramTokyoListProcessor())
                .split(body(LinkedHashSet.class))
                .to("direct:anime_program.page")
                .filter(header("notIgnore"))
                .to("direct:anime_program.insert");
        from("timer:program_series.watch?period=10m").autoStartup(false)
                .to("sql:select series_id, series_title from program_series where ignore_series?dataSource=ds")
                .process(new AnimeProgramIgnoreSeriesProcessor())
                .filter(header("ignoreSeriesUpdate"))
                .process(new AnimeProgramDeleteIgnoreSeriesProgramProcessor())
                .to("jdbc:ds");
        from("timer:foo?period=3m").autoStartup(false)
                .process(new AnimeProgramTokyoOnairProcessor())
                .split(body(LinkedHashSet.class))
                .setHeader("onair_flag").constant(true)
                .to("direct:anime_program.page")
                .filter(header("notIgnore"))
                .to("direct:anime_program.insert2");
        from("direct:anime_program.page").process(new AnimeProgramPageProcessor());
        from("direct:anime_program.insert").toF("sql:WITH upsert AS (%s RETURNING *) %s WHERE NOT EXISTS (SELECT * FROM upsert)?dataSource=ds", update, insert);
        from("direct:anime_program.insert2").toF("sql:WITH upsert AS (%s RETURNING *) %s WHERE NOT EXISTS (SELECT * FROM upsert)?dataSource=ds", update2, insert2);
    }

}

class AnimeProgramPageProcessor implements Processor {

    final SimpleDateFormat mysdf = new SimpleDateFormat("yyyy年M月d日H時mm分");
    final Pattern dayofweekPattern = Pattern.compile("^.*（([日月火水木金土])）.*$");
    final Pattern startTimePattern = Pattern.compile("^(.*?)（[日月火水木金土]）(.*?)～.*$");
    final Pattern endTimePattern = Pattern.compile("^(.*?)（[日月火水木金土]）.*?～(.*)$");

    AnimeProgramPageProcessor() {
        mysdf.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String page = exchange.getIn().getBody(String.class);
        Document document = Utility.getDocument("http://tv.yahoo.co.jp" + page);
        documentToHeader(exchange, document);
        exchange.getIn().setHeader("program_id", page);
    }

    public void documentToHeader(Exchange exchange, Document document) throws ParseException, IOException {
        Message in = exchange.getIn();
        in.setHeader("new_flag", !document.select(".new").isEmpty());
        in.setHeader("repeat_flag", !document.select(".repeat").isEmpty());
        in.setHeader("end_flag", !document.select(".end").isEmpty());
        Elements logo_els = document.select("img[title=ロゴ]");
        if (!logo_els.isEmpty()) {
            in.setHeader("logo_url", logo_els.first().attr("style").replaceFirst("^(.*?)\\((.*?)\\)(.*)$", "$2"));
        }
        in.setHeader("program_text", document.select(".pt15.pb15.pl10.pr10.clearfix").text().replace("\u00a0", " "));
        in.setHeader("station", document.select(".yjS.pb5p p").first().text().replace("\u00a0", " "));
        String time_exp = document.select(".pt5p em").first().text().replace("\u00a0", "");
        in.setHeader("time_exp", time_exp);
        in.setHeader("dayofweek", dayofweekPattern.matcher(time_exp).replaceAll("$1"));
        in.setHeader("start_time", mysdf.parse(startTimePattern.matcher(time_exp).replaceAll("$1$2")).getTime());
        in.setHeader("end_time", mysdf.parse(endTimePattern.matcher(time_exp).replaceAll("$1$2")).getTime());
        in.setHeader("program_title", document.select(".yjM.ttl").text().replace("\u00a0", " "));
        Elements review_els = document.select(".floatl.mitai a[href^=/review/]");
        String series_id = review_els.isEmpty() ? null : review_els.first().attr("href").replace("\u00a0", " ");
        in.setHeader("notIgnore", true);
        in.setHeader("series_id", series_id);
        if (series_id != null) {
            Map ignore = JsonResource.getInstance().get("ignoreSeries", Map.class);
            if (ignore.containsKey(series_id)) {
                in.setHeader("notIgnore", false);
            }
        }
        in.setHeader("series_title", document.select(".yjL.pb10").first().text().replace("\u00a0", " "));
        Elements next_els = document.select(".day_dataR.lh32.textC a[href]");
        if (!next_els.isEmpty()) {
            in.setHeader("next_id", next_els.first().attr("href"));
        }
    }
}

class AnimeProgramTokyoListProcessor implements Processor {

    final String domain = "http://tv.yahoo.co.jp";

    @Override
    public void process(Exchange exchange) throws Exception {
        String path = "/search/?q=&t=3&a=23&g=07&oa=1&s=1";
        LinkedHashSet<String> links = new LinkedHashSet<>();
        while (true) {
            Document document = Utility.getDocument(domain + path);
            Elements program_link_els = document.select(".programlist li a[href~=/program/\\d{6,}/]");
            for (Element e : program_link_els) {
                links.add(e.attr("href"));
            }
            Elements next_els = document.select(".pagelist.mb25 ul li a:contains(次へ)");
            if (next_els.isEmpty()) {
                break;
            } else {
                path = next_els.first().attr("href");
            }
        }
        exchange.getIn().setBody(links);
    }
}

class AnimeProgramTokyoOnairProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Document document = Utility.getDocument("http://tv.yahoo.co.jp/search/?g=07");
        Elements select = document.select(".programlist li:has(.onAir) a[href~=/program/\\d{6,}/]");
        LinkedHashSet<String> links = new LinkedHashSet<>();
        for (Element e : select) {
            links.add(e.attr("href"));
        }
        exchange.getIn().setBody(links);
    }
}

class AnimeProgramIgnoreSeriesProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        List<Map<String, Object>> body = exchange.getIn().getBody(List.class);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> map : body) {
            result.put((String) map.get("series_id"), map.get("series_title"));
        }
        exchange.getIn().setHeader("ignoreSeriesUpdate", JsonResource.getInstance().save("ignoreSeries", result, exchange));
    }
}

class AnimeProgramDeleteIgnoreSeriesProgramProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> ignore = JsonResource.getInstance().get("ignoreSeries", Map.class);
        StringBuilder sb = new StringBuilder("'test'");
        for (String key : ignore.keySet()) {
            sb.append(",'").append(key).append("'");
        }
        String string = new String(sb);
        String query = "delete from program where series_id in (%s)";
        exchange.getIn().setBody(String.format(query, string));
    }
}
