package com.mycode.kyokuhoku.services;

import com.mycode.kyokuhoku.JsonResource;
import com.mycode.kyokuhoku.Memory;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.camel.*;
import org.apache.camel.builder.RouteBuilder;

public class SeiyuGoodsRoute extends RouteBuilder {
    
    @Override
    public void configure() throws Exception {
        from("direct:seiyugoods.search")
                .setHeader("page", simple("${body[page]}"))
                .choice().when(Memory.hit("seiyugoods", simple("${header.page}")))
                .otherwise()
                .to("direct:seiyu_wiki_parse.parse")
                .to("direct:seiyu_wiki_parse.charname")
                .process(new SeiyuGoodsSearchProcessor())
                .process(Memory.save("seiyugoods", simple("${header.page}")))
                .end()
                .to("direct:websocket.setSend");
    }
    
}

class SeiyuGoodsSearchProcessor implements Processor {
    
    final Pattern ignoreChar = Pattern.compile("[\u0000-\u002f\u003a-\u0040\\u005b-\u0060\u007b-\u3040\u3095-\u309c\u309f-\u30a0\u30f7-\u30fb\u30ff-\u4dff\u9fa1-\uf928\uf92a-\uf9db\uf9dd-\ufa0d\ufa2e-\uff0f\uff1a-\uff20\uff3b-\uff40\uff5b-\uff65\uff9e-\uffff]");
    
    @Override
    public void process(Exchange exchange) throws Exception {
        LinkedHashMap<String, Set<String>> charNameSetMap = exchange.getIn().getBody(LinkedHashMap.class);
        ArrayList<Map> result = new ArrayList<>();
        HashSet<String> links = new HashSet<>();
        JsonResource instance = JsonResource.getInstance();
        Map<String, ArrayList<Map<String, String>>> amiamiItemMap = instance.get("amiamiItemMap", Map.class);
        for (String key : charNameSetMap.keySet()) {
            ArrayList<Map<String, String>> items = amiamiItemMap.get(key);
            if (items != null) {
                for (Map<String, String> item : items) {
                    String link = item.get("link");
                    for (String charName : charNameSetMap.get(key)) {
                        if (item.get("name").contains(charName)) {
                            Map<String, String> newItem = new LinkedHashMap<>();
                            for (String keykey : item.keySet()) {
                                newItem.put(keykey, item.get(keykey));
                            }
                            newItem.put("character", charName);
                            if (!links.contains(link)) {
                                result.add(newItem);
                                links.add(link);
                            }
                        }
                    }
                    if (!links.contains(link)) {
                        for (String charName : charNameSetMap.get(key)) {
                            if (ignoreChar.matcher(item.get("name")).replaceAll("").contains(ignoreChar.matcher(charName).replaceAll(""))) {
                                Map<String, String> newItem = new LinkedHashMap<>();
                                for (String keykey : item.keySet()) {
                                    newItem.put(keykey, item.get(keykey));
                                }
                                newItem.put("character", charName);
                                if (!links.contains(link)) {
                                    result.add(newItem);
                                    links.add(link);
                                }
                            }
                        }
                    }
                    if (!links.contains(link)) {
                        result.add(item);
                        links.add(link);
                    }
                }
            }
        }
        exchange.getIn().setBody(result);
    }
}
