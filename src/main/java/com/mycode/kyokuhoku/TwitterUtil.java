package com.mycode.kyokuhoku;

import java.util.Map;
import twitter4j.Twitter;
import twitter4j.TwitterFactory;
import twitter4j.conf.ConfigurationBuilder;

public class TwitterUtil {

    public static Twitter getTwitterInstance(Map<String, Object> headers) {
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.setDebugEnabled(true)
                .setOAuthConsumerKey((String) headers.get("consumer_key"))
                .setOAuthConsumerSecret((String) headers.get("consumer_secret"))
                .setOAuthAccessToken((String) headers.get("access_token"))
                .setOAuthAccessTokenSecret((String) headers.get("access_token_secret"));
        TwitterFactory tf = new TwitterFactory(cb.build());
        return tf.getInstance();
    }
}
