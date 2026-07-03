package com.example;

/** Implements the nested callback interface; onOpen is only ever called through Menu.Callback. */
public class MenuImpl implements Menu.Callback {
    @Override
    public String onOpen(String name) {
        return "open:" + name;
    }
}
