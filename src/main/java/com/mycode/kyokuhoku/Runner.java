package com.mycode.kyokuhoku;

import com.mycode.kyokuhoku.routes.DynamicRoute;
import com.mycode.kyokuhoku.routes.JsonResourceRoute;
import com.mycode.kyokuhoku.routes.UtilityRoute;
import com.mycode.kyokuhoku.routes.WebSocketRoute;
import com.mycode.kyokuhoku.services.AmebloRoute;
import com.mycode.kyokuhoku.services.AmiamiRoute;
import com.mycode.kyokuhoku.services.AnimeProgramRoute;
import com.mycode.kyokuhoku.services.AotagaiRoute;
import com.mycode.kyokuhoku.services.ChatRoute;
import com.mycode.kyokuhoku.services.EnglishQuizRoute;
import com.mycode.kyokuhoku.services.KoepotaRoute;
import com.mycode.kyokuhoku.services.SeiyuGoodsRoute;
import com.mycode.kyokuhoku.services.SeiyuRoute;
import com.mycode.kyokuhoku.services.SeiyuWikiParseRoute;
import java.util.Properties;
import org.apache.camel.main.Main;

public class Runner {

    public static void main(String[] args) throws Exception {
        Main main = new Main();
        main.bind("ds", new MyDataSourceService().getDataSource());
        main.addRouteBuilder(new DynamicRoute());
        main.addRouteBuilder(new JsonResourceRoute());
        main.addRouteBuilder(new UtilityRoute());
        main.addRouteBuilder(new WebSocketRoute());

        main.addRouteBuilder(new KoepotaRoute());
        main.addRouteBuilder(new AmebloRoute());
        main.addRouteBuilder(new AmiamiRoute());
        main.addRouteBuilder(new AnimeProgramRoute());
        main.addRouteBuilder(new AotagaiRoute());
        main.addRouteBuilder(new ChatRoute());
        main.addRouteBuilder(new EnglishQuizRoute());
        main.addRouteBuilder(new SeiyuGoodsRoute());
        main.addRouteBuilder(new SeiyuRoute());
        main.addRouteBuilder(new SeiyuWikiParseRoute());
        main.start();
        if (Settings.isLocal) {
            startHawtio();
        }
    }

    public static void startHawtio() throws Exception {
        Properties props = System.getProperties();
        props.setProperty("hawtio.authenticationEnabled", "false");
        props.setProperty("hawtio.offline", "false");
        props.setProperty("hawtio.dirname", "/Temp/hawtio/");
        String hawtioWar = new Runner().getClass().getResource("hawtio-default-1.4.48.war").getPath();
        io.hawt.embedded.Main main = new io.hawt.embedded.Main();
        main.setWarLocation(String.valueOf(hawtioWar));
        main.setPort(9090);
        main.run();
    }
}
