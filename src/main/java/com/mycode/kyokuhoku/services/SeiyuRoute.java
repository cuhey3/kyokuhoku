package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.Utility;
import java.io.IOException;
import java.util.ArrayList;
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

public class SeiyuRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        String insert = "INSERT INTO seiyu (name, ameblo_url, twitter_url) SELECT :#${header.name}, :#${header.ameblo_url}, :#${header.twitter_url}";
        String upsert = "UPDATE seiyu SET ameblo_url=case when ameblo_url is null then :#${header.ameblo_url} else ameblo_url end, twitter_url=case when twitter_url is null then :#${header.twitter_url} else twitter_url end WHERE name=:#${header.name}";

        from("direct:seiyu.getInfo")
                .process(Utility.urlEncode(body(), "encodedName"))
                .process(Utility.GetDocumentProcessor(simple("http://ja.wikipedia.org/w/api.php?action=parse&prop=externallinks&page=${header.encodedName}&format=xml&redirects")))
                .filter(new SeiyuGetInfoPredicate())
                .toF("sql:WITH upsert AS (%s RETURNING *) %s WHERE NOT EXISTS (SELECT * FROM upsert)?dataSource=ds", upsert, insert);

        from("timer:seiyu.crawl?period=24h").autoStartup(false).routeId("seiyu.crawl")
                .to("sql:select name from seiyu where seiyu_ignore is null?dataSource=ds")
                .split(body(List.class)).throttle(1).timePeriodMillis(30000)
                .setBody(simple("${body[name]}"))
                .to("direct:seiyu.getInfo");

        from("direct:seiyu.new")
                .process(new SeiyuNewProcessor())
                .split(body(List.class))
                .to("direct:seiyu.getInfo");

        from("timer:seiyu.categorymembers?period=1h").autoStartup(false).routeId("seiyu.categorymembers")
                .process(new SeiyuCategoryMembersProcessor())
                .filter(header("seiyuNameUpdate"))
                .to("direct:seiyu.new")
                .to("direct:koepota.existUpdateSeiyu"); // external:koepota
/*        from("seda:seiyu.twitter")
                .to("sql:select * from twitter_oauth?dataSource=ds")
                .setBody(simple("${body[0]}")).to("direct:utility.mapToHeader")
                .to("sql:select twitter_url from seiyu where twitter_url is not null and seiyu_ignore is null and koepota_exist_now?dataSource=ds")
                .process(new Processor() {

                    @Override
                    public void process(Exchange exchange) throws Exception {
                        Map<String, Object> headers = exchange.getIn().getHeaders();
                        ConfigurationBuilder cb = new ConfigurationBuilder();
                        cb.setDebugEnabled(true)
                        .setOAuthConsumerKey((String) headers.get("consumer_key"))
                        .setOAuthConsumerSecret((String) headers.get("consumer_secret"))
                        .setOAuthAccessToken((String) headers.get("access_token"))
                        .setOAuthAccessTokenSecret((String) headers.get("access_token_secret"));
                        TwitterFactory tf = new TwitterFactory(cb.build());
                        Twitter twitter = tf.getInstance();
                        UserList userList = twitter.getUserLists("Cu_hey").get(0);
                        long listId = userList.getId();
                        List<Map<String, String>> body = exchange.getIn().getBody(List.class);
                        ArrayList<String> users = new ArrayList<>();
                        for (Map<String, String> map : body) {
                            users.add(map.get("twitter_url").replaceFirst("(https?://)?twitter.com/", "").replaceFirst("/$", ""));
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
                        for (int i = 0; i < users.size(); i++) {
                            sub.add(users.get(i));
                            if (sub.size() == 50) {
                                twitter.createUserListMembers(listId, sub.toArray(new String[50]));
                                sub = new ArrayList<>();
                            }
                        }
                        if (!sub.isEmpty()) {
                            twitter.createUserListMembers(listId, sub.toArray(new String[sub.size()]));
                        }
                        System.out.println(users.size() + " updated.");
                    }
                });*/
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
            if (ameblo_url != null || twitter_url != null || koepota_exist) {
                in.setHeader("ameblo_url", ameblo_url);
                in.setHeader("twitter_url", twitter_url);
                in.setHeader("name", name);
                return true;
            } else {
                return false;
            }
        } catch (IOException ex) {
            return false;
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
