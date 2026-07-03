package com.example;

/** Nested callback interface — mirrors androidx patterns like MenuPresenter.Callback. open() invokes
 *  the callback through the nested interface type, so the call is recorded against Menu.Callback.onOpen
 *  (whose top-level class is Menu, but the method lives in the inner class Menu$Callback). */
public class Menu {
    public interface Callback {
        String onOpen(String name);
    }

    public String open(Callback cb, String n) {
        return cb.onOpen(n);
    }
}
