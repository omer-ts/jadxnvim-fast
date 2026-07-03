package com.example;

public class Upper extends Base {
    @Override
    public String handle(String s) {
        return s.toUpperCase();
    }

    @Override
    public String describe() {
        return "upper";
    }
}
