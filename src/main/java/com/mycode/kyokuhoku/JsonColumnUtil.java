package com.mycode.kyokuhoku;

import com.github.fge.jsonpatch.diff.JsonDiff;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;

public class JsonColumnUtil {

    private final String id;
    private final String oldJson;
    private final List diff;
    private final Kind kind;

    public JsonColumnUtil(Map row, Kind kind, Exchange exchange) throws IOException {
        this.kind = kind;
        this.id = (String) row.get(kind.idColumnName);
        this.oldJson = (String) row.get(kind.jsonColumnName);
        String diffString = (String) row.get(kind.diffColumnName);
        if (diffString != null && diffString.startsWith("[")) {
            this.diff = MyJsonUtil.mapper().readValue(diffString, List.class);
        } else {
            this.diff = new ArrayList<>();
        }
    }

    public <T extends Object> T getObject(Class<T> type) throws IOException {
        if (oldJson == null) {
            return null;
        } else {
            return MyJsonUtil.mapper().readValue(oldJson, type);
        }
    }

    public void save(Object o, Exchange exchange) throws IOException {
        final Pattern ngWord = Pattern.compile("^(日本|女性|男性|声優|ABO式血液型|Twitter|センチメートル|俳優|舞台|女優|歌手|シンガーソングライター)$");
        String jsonString = MyJsonUtil.getJsonString(o);
        String diffString = null;
        if (oldJson != null) {
            diffString = JsonDiff.asJson(MyJsonUtil.mapper().readTree(oldJson), MyJsonUtil.mapper().readTree(jsonString)).toString();
        }
        if (diffString == null || !diffString.equals("[]")) {
            ProducerTemplate pt = exchange.getContext().createProducerTemplate();
            Exchange e = new DefaultExchange(exchange.getContext());
            e.getIn().setHeader("id", id);
            e.getIn().setHeader("json", jsonString);
            e.getIn().setHeader("update", System.currentTimeMillis());
            if (diffString != null) {
                List readValue = MyJsonUtil.mapper().readValue(diffString, List.class);
                Iterator iterator = readValue.iterator();
                while (iterator.hasNext()) {
                    Map next = (Map) iterator.next();
                    if (ngWord.matcher(((String) next.get("path")).replaceFirst("^/", "")).find()) {
                        iterator.remove();
                    }
                }
                if (!readValue.isEmpty()) {
                    Map map = new LinkedHashMap<>();
                    map.put("time", System.currentTimeMillis());
                    map.put("diff", readValue);
                    pushDiff(map);

                }
                e.getIn().setHeader("diff", getDiffString());
            }
            pt.send(kind.endpoint, e);
        }
    }

    public void pushDiff(Object o) {
        diff.add(o);
        while (diff.size() > 20) {
            diff.remove(0);
        }
    }

    public String getDiffString() throws IOException {
        return MyJsonUtil.getJsonString(diff);
    }

    public enum Kind {

        SEIYU_TOLINKS("seda:json_resource.save.seiyu.to_links", "name", "to_links", "to_links_diff", "to_links_update");
        private final String endpoint;
        private final String idColumnName;
        private final String jsonColumnName;
        private final String diffColumnName;
        private final String updateColumnName;

        private Kind(String endpoint, String idColumnName, String jsonColumnName, String diffColumnName, String updateColumnName) {
            this.endpoint = endpoint;
            this.idColumnName = idColumnName;
            this.jsonColumnName = jsonColumnName;
            this.diffColumnName = diffColumnName;
            this.updateColumnName = updateColumnName;
        }
    }
}
