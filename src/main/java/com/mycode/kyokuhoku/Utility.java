package com.mycode.kyokuhoku;

import java.util.Map;
import java.util.Map.Entry;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Processor;
import org.jsoup.Connection;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class Utility {

    public static Document getDocument(String url) {
        Document doc = null;
        int retry = 0;
        while (doc == null && retry < 10) {
            try {
                doc = Jsoup.connect(url).maxBodySize(Integer.MAX_VALUE).timeout(Integer.MAX_VALUE).get();
            } catch (Throwable t) {
                retry++;
            }
        }
        return doc;
    }

    public static byte[] getBytes(String url) {
        Response response = null;
        int retry = 0;
        while (response == null & retry < 10) {
            try {
                response = Jsoup.connect(url).maxBodySize(Integer.MAX_VALUE).timeout(Integer.MAX_VALUE).ignoreContentType(true).execute();
            } catch (Throwable t) {
                retry++;
            }
        }
        if (response.bodyAsBytes().length != 0) {
            return response.bodyAsBytes();
        } else {
            return new byte[]{};
        }
    }

    public static void mapToHeader(Exchange exchange, Map<String, Object> map, boolean rewrite) {
        Map<String, Object> headers = exchange.getIn().getHeaders();
        for (Entry<String, Object> entry : map.entrySet()) {
            if (rewrite || !headers.containsKey(entry.getKey())) {
                exchange.getIn().setHeader(entry.getKey(), entry.getValue());
            }
        }
    }

    public static String getJson(String url) {
        int retry = 0;
        Connection.Response response = null;
        while (response == null && retry < 10) {
            try {
                response = Jsoup.connect(url).ignoreContentType(true).method(Connection.Method.GET).execute();
            } catch (Throwable t) {
                retry++;
            }
        }
        if (response != null) {
            return response.body();
        } else {
            return "{}";
        }
    }

    public static Processor GetBytesProcessor(final Expression urlExp, final Expression fileNameExp) {
        return new Processor() {

            @Override
            public void process(Exchange exchange) throws Exception {
                exchange.getIn().setBody(Utility.getBytes(urlExp.evaluate(exchange, String.class)));
                exchange.getIn().setHeader(Exchange.FILE_NAME, fileNameExp.evaluate(exchange, String.class));
            }
        };
    }

    public static Processor GetDocumentProcessor(final Expression urlExp) {
        return new Processor() {

            @Override
            public void process(Exchange exchange) throws Exception {
                exchange.getIn().setBody(Utility.getDocument(urlExp.evaluate(exchange, String.class)));
            }
        };
    }

    public static Processor urlEncode(final Expression fromExp, final String header) {
        return new Processor() {

            @Override
            public void process(Exchange exchange) throws Exception {
                exchange.getIn().setHeader(header, fromExp.evaluate(exchange, Object.class));
            }
        };
    }
}
