package com.example;

/** A method with a multi-line if/else block (side-effecting field writes jadx keeps as separate
 *  lines), for exercising code folding. */
public class Folds {
    int a;
    int b;
    int c;

    public void classify(int x) {
        if (x > 10) {
            this.a = x;
            this.b = x * 2;
            this.c = x - 1;
        } else {
            this.a = -x;
            this.b = 0;
            this.c = 7;
        }
    }
}
