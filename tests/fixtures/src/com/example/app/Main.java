package com.example.app;

import com.example.Greeter;
import com.example.Hello;
import com.example.util.Strings;

public class Main {
    public static void main(String[] args) {
        // interface-typed call: go-to-def should offer the implementations; find-usages on
        // Hello.greet should reach this call site through the base method.
        Greeter g = new Hello();
        System.out.println(g.greet("world"));

        // direct call on the concrete type
        Hello h = new Hello();
        System.out.println(h.getCount());

        System.out.println(Strings.token());
    }
}
