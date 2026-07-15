package com.example;

/** Calls the same method twice from one body, so find-usages yields two distinct call sites in this
 *  class — exercises that selecting a usage jumps to its OWN call line (relocation / ordinal), not
 *  collapsing both onto the same spot. */
public class Repeated {
    public void report(Hello a, Hello b) {
        // Two separate statements with side effects, so jadx keeps them on distinct lines (rather
        // than inlining both calls onto one), giving find-usages two distinct call sites.
        System.out.println(a.getCount());
        System.out.println(b.getCount());
    }
}
