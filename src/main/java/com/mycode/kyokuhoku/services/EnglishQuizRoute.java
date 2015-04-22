package com.mycode.kyokuhoku.services;

import com.google.common.collect.HashBiMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.websocket.WebsocketConstants;
import static com.mycode.kyokuhoku.services.EnglishQuizRoute.*;
import org.apache.camel.Predicate;

public class EnglishQuizRoute extends RouteBuilder {

    public static final Map<String, String> keyToAnswer = new LinkedHashMap<>();
    public static final HashBiMap<String, String> keyBiUser = HashBiMap.create();
    public static final Map<String, Integer> userEloratings = new LinkedHashMap<>();
    public static final Map<String, Integer> wordEloratings = new LinkedHashMap<>();
    public static final String MESSAGE_ENDPOINT = "seda:message/english_quiz";

    @Override
    public void configure() throws Exception {
        from("direct:english_quiz.login")
                .to("direct:utility.mapBodyToHeader")
                .to("sql:select * from quizuser where username = :#${header.username} and password = :#${header.password}?dataSource=ds")
                .choice().when(new EnglishQuizLoginPredicate())
                .to("seda:english_quiz.elo")
                .setHeader("message").constant("ログインに成功しました。").to(MESSAGE_ENDPOINT)
                .otherwise()
                .setHeader("message").constant("ログインに失敗しました").to(MESSAGE_ENDPOINT);

        from("direct:english_quiz.answer")
                .choice().when(new EnglishQuizAnswerValidater()).to("direct:english_quiz.answerMatch")
                .otherwise()
                .setHeader("message").constant("回答できません。").to(MESSAGE_ENDPOINT);

        from("direct:english_quiz.answerMatch")
                .choice().when(new EnglishQuizAnswerPredicate())
                .setHeader("message").constant("正解です").to(MESSAGE_ENDPOINT)
                .otherwise()
                .setHeader("message").constant("不正解です").to(MESSAGE_ENDPOINT)
                .setHeader("message", header("word")).to(MESSAGE_ENDPOINT)
                .end()
                .to("seda:english_quiz.updateUserElo")
                .to("seda:english_quiz.updateWordElo")
                .to("seda:english_quiz.elo");

        from("direct:english_quiz.question")
                .choice().when(new EnglishQuizQuestionValidater())
                .setHeader("random_max").constant(1508)
                .to("direct:utility.getRandom")
                .to("sql:select * from words where id = :#${header.random}?dataSource=ds")
                .process(new EnglishQuizQuestionProcessor()).to("direct:websocket.setSend")
                .otherwise().setHeader("message").constant("ログインしていません。").to(MESSAGE_ENDPOINT);

        from("direct:english_quiz.createUser")
                .to("direct:utility.mapBodyToHeader")
                .to("sql:select * from quizuser where username = :#${header.username}?dataSource=ds")
                .choice().when(new EnglishQuizUserNotExistPredicate())
                .to("direct:english_quiz.insertUser")
                .otherwise()
                .setHeader("message").constant("ユーザー名はすでに存在しています。").to(MESSAGE_ENDPOINT);

        from("direct:english_quiz.insertUser")
                .to("sql:insert into quizuser (username,password) select :#${header.username},:#${header.password}?dataSource=ds")
                .setHeader("message").constant("ユーザーを作成しました。").to(MESSAGE_ENDPOINT);
        from("seda:english_quiz.updateUserElo")
                .to("sql:update quizuser set elo = :#${header.user_update_elo} where username = :#${header.username}?dataSource=ds");
        from("seda:english_quiz.updateWordElo")
                .to("sql:update words set elo = :#${header.word_update_elo} where word = :#${header.word}?dataSource=ds");
        from("seda:english_quiz.elo").process(new EnglishQuizSendUserElo()).to("direct:send/english_quiz");
    }
}

class EnglishQuizLoginPredicate implements Predicate {

    @Override
    public boolean matches(Exchange exchange) {
        String key = exchange.getIn().getHeader(WebsocketConstants.CONNECTION_KEY, String.class);
        List<Map<String, Object>> users = exchange.getIn().getBody(List.class);
        if (users.size() == 1) {
            Map user = users.get(0);
            String username = (String) user.get("username");
            String removedKey = keyBiUser.inverse().remove(username);
            keyBiUser.put(key, username);
            if (!userEloratings.containsKey(username)) {
                userEloratings.put(username, (Integer) user.get("elo"));
            }
            exchange.getIn().setHeader("user_elo", userEloratings.get(username));
            if (removedKey != null) {
                String removedAnswer = keyToAnswer.remove(removedKey);
                if (removedAnswer != null) {
                    keyToAnswer.put(key, removedAnswer);
                }
            }
            return true;
        } else {
            return false;
        }
    }
}

class EnglishQuizAnswerPredicate implements Predicate {

    @Override
    public boolean matches(Exchange exchange) {
        Map map = exchange.getIn().getBody(Map.class);
        String answer = (String) map.get("answer");
        String key = exchange.getIn().getHeader(WebsocketConstants.CONNECTION_KEY, String.class);
        String username = keyBiUser.get(key);
        String rightAnswer = keyToAnswer.get(key);
        keyToAnswer.remove(key);
        updateElo(username, rightAnswer, answer.equals(rightAnswer));
        exchange.getIn().setHeader("word", rightAnswer);
        exchange.getIn().setHeader("username", username);
        exchange.getIn().setHeader("word_update_elo", wordEloratings.get(rightAnswer));
        exchange.getIn().setHeader("user_update_elo", userEloratings.get(username));
        exchange.getIn().setHeader("user_elo", userEloratings.get(username));
        return answer.equals(rightAnswer);
    }

    public void updateElo(String username, String word, boolean userWin) {
        int sign = userWin ? 1 : -1;
        Integer userElo = userEloratings.get(username);
        Integer wordElo = wordEloratings.get(word);
        int delta = 16 + ((int) Math.round((wordElo - userElo) * 0.04)) * sign;
        if (delta < 1) {
            delta = 1;
        } else if (delta > 31) {
            delta = 31;
        }
        userEloratings.put(username, userElo + delta * sign);
        wordEloratings.put(word, wordElo - delta * sign);
    }
}

class EnglishQuizAnswerValidater implements Predicate {

    @Override
    public boolean matches(Exchange exchange) {
        String key = exchange.getIn().getHeader(WebsocketConstants.CONNECTION_KEY, String.class);
        return keyToAnswer.containsKey(key) && keyBiUser.containsKey(key);
    }
}

class EnglishQuizQuestionProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Map map = (Map) exchange.getIn().getBody(List.class).get(0);
        map.remove("id");
        String word = (String) map.remove("word");
        keyToAnswer.put(exchange.getIn().getHeader(WebsocketConstants.CONNECTION_KEY, String.class), word);
        wordEloratings.put(word, (Integer) map.get("elo"));
        exchange.getIn().setBody(map);
    }
}

class EnglishQuizQuestionValidater implements Predicate {

    @Override
    public boolean matches(Exchange exchange) {
        String key = exchange.getIn().getHeader(WebsocketConstants.CONNECTION_KEY, String.class);
        return keyBiUser.containsKey(key);
    }
}

class EnglishQuizUserNotExistPredicate implements Predicate {

    @Override
    public boolean matches(Exchange exchange) {
        List list = exchange.getIn().getBody(List.class);
        return list.isEmpty();
    }
}

class EnglishQuizSendUserElo implements Processor {

    @Override
    public void process(Exchange exchange) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("elo", exchange.getIn().getHeader("user_elo"));
        exchange.getIn().setBody(map);
    }
}
