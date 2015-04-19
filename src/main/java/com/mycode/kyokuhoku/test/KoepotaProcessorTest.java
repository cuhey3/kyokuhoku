package com.mycode.kyokuhoku.test;

import com.mycode.kyokuhoku.MyDataSourceService;
import com.mycode.kyokuhoku.routes.JsonResourceRoute;
import com.mycode.kyokuhoku.services.KoepotaRoute;
import org.apache.camel.main.Main;

public class KoepotaProcessorTest {

    public static void main(String[] args) throws Exception {
        Main main = new Main();
        main.bind("ds", new MyDataSourceService().getDataSource());
        main.addRouteBuilder(new JsonResourceRoute());
        main.addRouteBuilder(new KoepotaRoute());
        main.run();
    }
}
