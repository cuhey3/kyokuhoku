package com.mycode.kyokuhoku;

public class Settings {

    public static final String PORT = System.getenv("PORT") == null ? "80" : System.getenv("PORT");
    public static final String PUBLIC_RESOURCE_PATH = "public";
    public static final String PRIVATE_RESOURCE_PATH = "private";
}
