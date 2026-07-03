package com.example;

public class Hello implements Greeter {
    private int count;

    @Override
    public String greet(String name) {
        this.count++;
        return "Hello, " + name;
    }

    public int getCount() {
        return this.count;
    }
}
