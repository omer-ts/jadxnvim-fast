package com.example;

/** Uses methods through the base type (so calls resolve to Base's declarations) and through a
 *  concrete implementation type (so the call resolves to MenuImpl's declaration, not the interface).
 *  find-usages on the interface method must still surface the latter, like jadx-gui. */
public class Caller {
    public String go(Base b, String s) {
        return b.describe() + ":" + b.process(s);
    }

    public String direct(String s) {
        return new MenuImpl().onOpen(s); // concrete-typed call -> recorded against MenuImpl.onOpen
    }
}
