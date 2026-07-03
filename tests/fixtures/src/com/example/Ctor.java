package com.example;

/** Has an explicit constructor so a rename can be attempted via the constructor name. */
public class Ctor {
    private final int n;

    public Ctor(int n) {
        this.n = n;
    }

    public int get() {
        return this.n;
    }
}
