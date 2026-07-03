package com.example.util;

public final class Strings {
    /** A distinctive constant for exercising full-text search. */
    public static final String SECRET_TOKEN = "s3cr3t_marker_9f2a";

    public static String token() {
        return SECRET_TOKEN;
    }

    private Strings() {
    }
}
