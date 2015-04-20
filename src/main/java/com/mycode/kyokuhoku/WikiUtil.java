package com.mycode.kyokuhoku;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import org.jsoup.nodes.Document;

public class WikiUtil {

    public static String getWikiTitle(String title) throws UnsupportedEncodingException {
        Document doc;
        doc = Utility.getDocument("http://ja.wikipedia.org/w/api.php?action=query&format=xml&titles=" + URLEncoder.encode(title, "UTF-8"));
        if (doc == null || doc.select("page[pageid]").isEmpty()) {
            doc = Utility.getDocument("http://ja.wikipedia.org/w/api.php?action=query&format=xml&titles=" + URLEncoder.encode(title.replace("！", "!").replace("？", "?").replace("；", ";").replace("’", "'").replace("＆", "&").replace("：", ":").replace("＊", "*").replace("，", ",").replace("＠", "@"), "UTF-8"));

        }
        if (doc == null || doc.select("page[pageid]").isEmpty()) {
            return null;
        } else {
            return doc.select("page[pageid]").attr("title");
        }
    }
}
