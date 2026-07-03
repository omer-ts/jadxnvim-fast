package com.example;

/** A method with local variables, for exercising variable renaming. */
public class Vars {
    public int compute(int seed) {
        int total = seed;
        for (int step = 0; step < 3; step++) {
            total += step * seed;
        }
        return total;
    }
}
