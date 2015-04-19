package com.mycode.kyokuhoku.test;

import com.mycode.kyokuhoku.services.SeiyuWikiParseRoute;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.DefaultExchange;

public class SeiyuWikiParseRouteTest {

    public static void main(String[] args) throws Exception {
        CamelContext context = new DefaultCamelContext();
        context.addRoutes(new SeiyuWikiParseRoute());
        ProducerTemplate pt = context.createProducerTemplate();
        DefaultExchange exchange = new DefaultExchange(context);
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("page", "大久保瑠美");
        exchange.getIn().setBody(map);
        context.start();
        Map result = pt.send("direct:seiyu_wiki_parse.parse", exchange).getIn().getBody(Map.class);
        Map<String, Object> links = (Map) result.get("links");
        List<Map> tokens = (List) result.get("tokens");
        Pattern p = Pattern.compile("[\\(（]([^\\(（）\\)]+?)[）\\)][ 　]*$");
        Matcher m;
        TreeSet<String> set = new TreeSet<>();
        for (String key : links.keySet()) {
            List<Integer> list = (List<Integer>) ((Map) links.get(key)).get("t");
            for (Integer i : list) {
                String[] replace = ((String) tokens.get(i).get("s")).split("\r\n|\n|\r");
                for (String s : replace) {
                    if (s.contains(key) && (m = p.matcher(s)).find()) {
                        String group = m.group(1);
                        group = group.replaceAll("'''(.+?)'''", "$1");
                        group = group.replaceAll("\\[\\[(.+?)\\]\\]", "$1");
                        String[] split = group.split("( / |、|\\|)");
                        for (String sp : split) {
                            set.add(key + "\t" + sp);
                        }
                    }
                }
            }
        }
        for(String s : set){
            System.out.println(s);
        }
    }
}
