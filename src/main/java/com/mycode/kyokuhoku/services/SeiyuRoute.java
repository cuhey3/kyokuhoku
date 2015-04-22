package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.TwitterUtil;
import com.mycode.kyokuhoku.Utility;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import twitter4j.PagableResponseList;
import twitter4j.Twitter;
import twitter4j.User;
import twitter4j.UserList;

public class SeiyuRoute extends RouteBuilder {

    final JsonResource jsonResource = JsonResource.getInstance();

    @Override
    public void configure() throws Exception {
        String insert = "INSERT INTO seiyu (name, ameblo_url, twitter_url) SELECT :#${header.name}, :#${header.ameblo_url}, :#${header.twitter_url}";
        String update = "UPDATE seiyu SET ameblo_url=case when ameblo_url is null then :#${header.ameblo_url} else ameblo_url end, twitter_url=case when twitter_url is null then :#${header.twitter_url} else twitter_url end WHERE name=:#${header.name}";

        from("direct:seiyu.getInfo")
                .process(Utility.urlEncode(body(String.class), "encodedName"))
                .process(Utility.getDocumentProcessor(simple("http://ja.wikipedia.org/w/api.php?action=parse&prop=externallinks&page=${header.encodedName}&format=xml&redirects")))
                .filter(new SeiyuGetInfoPredicate())
                .toF("sql:WITH upsert AS (%s RETURNING *) %s WHERE NOT EXISTS (SELECT * FROM upsert)?dataSource=ds", update, insert)
                .to("log:seiyu.getInfo.sql?showHeaders=true")
                .filter(header("exintro").isNull())
                .to("seda:seiyuwikiparse.getIntro");

        from("timer:seiyu.crawl?period=24h").autoStartup(false).routeId("seiyu.crawl")
                .to("sql:select name,twitter_url, ameblo_url, koepota_exist, exintro from seiyu where seiyu_ignore is null and (twitter_url is null or ameblo_url is null)?dataSource=ds")
                .split(body(List.class)).throttle(1).timePeriodMillis(60000)
                .to("direct:utility.mapBodyToHeader")
                .setBody(simple("${header.name}"))
                .to("direct:seiyu.getInfo");

        from("direct:seiyu.newcomer")
                .process(new SeiyuGetNewcomerProcessor())
                .split(body(List.class))
                .to("direct:seiyu.getInfo");

        from("timer:seiyu.categorymembers?period=1h").autoStartup(false).routeId("seiyu.categorymembers")
                .filter(new SeiyuCategoryMembersHasNewcomerPredicate())
                .to("direct:seiyu.newcomer")
                .to("direct:koepota.updateAction"); // external:koepota

        from("seda:seiyu.twitter")
                .to("sql:select * from twitter_oauth?dataSource=ds")
                .setBody(simple("${body[0]}")).to("direct:utility.mapBodyToHeader")
                .to("sql:select twitter_url from seiyu where twitter_url is not null and seiyu_ignore is null and koepota_exist_now?dataSource=ds")
                .process(Utility.mapListToListByOneField("twitter_url"))
                .process(new SeiyuUpdateTwitterListProcessor())
                .filter(jsonResource.saveWithCheck("seiyuTwitterList", simple("${body}")))
                .to("log:seiyu.twitter.update?showBody=false");
    }
}

class SeiyuCategoryMembersHasNewcomerPredicate implements Predicate {

    @Override
    public boolean matches(Exchange exchange) {
        try {
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
            return JsonResource.getInstance().save("seiyuName", seiyuName, exchange);
        } catch (IOException ex) {
            System.out.println("SeiyuCategoryMembersHasNewcomerPredicate error: ");
            ex.printStackTrace();
            return false;
        }
    }
}

class SeiyuGetInfoPredicate implements Predicate {

    @Override
    public boolean matches(Exchange exchange) {
        Message in = exchange.getIn();
        Document doc = in.getBody(Document.class);
        if (doc == null || !doc.select("error[code=missingtitle]").isEmpty()) {
            return false;
        }
        String ameblo_url = null, twitter_url, name;
        if (doc.select("redirects r").isEmpty()) {
            name = doc.select("parse[title]").first().attr("title");
        } else {
            name = doc.select("redirects r").first().attr("from");
        }
        Elements el = doc.select("externallinks el:matches(^https?://ameblo.jp/[^/]+/?$)");
        if (!el.isEmpty() && !el.text().contains("wakeupgirls")) {
            ameblo_url = getSpecificLink(el, doc);
        }
        el = doc.select("externallinks el:matches(^https?://twitter.com/[^/%@ ]+/?$)");
        twitter_url = getSpecificLink(el, doc);
        try {
            Map<String, String> koepotaMembers = JsonResource.getInstance().get("koepotaMembers", Map.class);
            boolean koepota_exist = koepotaMembers.containsKey(name.replaceFirst(" \\(.+\\)$", ""));
            if (in.getHeader("ameblo_url") == null && ameblo_url != null
                    || in.getHeader("twitter_url") == null && twitter_url != null
                    || in.getHeader("koepota_exist") == null && koepota_exist) {
                in.setHeader("ameblo_url", ameblo_url);
                in.setHeader("twitter_url", twitter_url);
                in.setHeader("name", name);
                return true;
            }
        } catch (IOException ex) {
            return false;
        }
        return false;
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

class SeiyuGetNewcomerProcessor implements Processor {

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

class SeiyuUpdateTwitterListProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Twitter twitter = TwitterUtil.getTwitterInstance(exchange.getIn().getHeaders());
        UserList userList = twitter.getUserLists("Cu_hey").get(0);
        long listId = userList.getId();
        List<String> body = exchange.getIn().getBody(List.class);
        ArrayList<String> users = new ArrayList<>();
        for (String url : body) {
            users.add(url.replaceFirst("(https?://)?twitter.com/", "").replaceFirst("/$", ""));
        }
        PagableResponseList<User> userListMembers = twitter.getUserListMembers(listId, -1L);
        Iterator<User> iterator = userListMembers.iterator();
        while (iterator.hasNext()) {
            User user = iterator.next();
            String screenName = user.getScreenName();
            if (users.contains(screenName)) {
                users.remove(screenName);
            } else {
                twitter.destroyUserListMember(listId, screenName);
            }
        }
        ArrayList<String> sub = new ArrayList<>();
        for (String user : users) {
            sub.add(user);
            if (sub.size() == 50) {
                twitter.createUserListMembers(listId, sub.toArray(new String[50]));
                sub = new ArrayList<>();
            }
        }
        if (!sub.isEmpty()) {
            twitter.createUserListMembers(listId, sub.toArray(new String[sub.size()]));
        }
        userListMembers = twitter.getUserListMembers(listId, -1L);
        userListMembers.iterator();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        while (iterator.hasNext()) {
            User user = iterator.next();
            result.put(user.getName(), user.getScreenName());
        }
        exchange.getIn().setBody(result);
    }
}
