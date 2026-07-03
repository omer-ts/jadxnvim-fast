package com.example;

/** Template-method base. process() (concrete) calls the abstract handle(); describe() is a concrete
 *  method that subclasses override. A find-usages on either override must surface calls made through
 *  the base type — whether the base declaration is abstract (handle) or concrete (describe). */
public abstract class Base {
    public abstract String handle(String s);

    public String process(String s) {
        return "processed:" + handle(s);
    }

    public String describe() {
        return "base";
    }
}
