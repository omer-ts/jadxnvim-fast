package com.example;

/** Uses methods through the base type, so the calls resolve to Base's declarations. */
public class Caller {
    public String go(Base b, String s) {
        return b.describe() + ":" + b.process(s);
    }
}
