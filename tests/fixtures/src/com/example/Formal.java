package com.example;

public class Formal implements Greeter {
    @Override
    public String greet(String name) {
        return "Good day, " + name;
    }
}
