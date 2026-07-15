package com.example;

/** An APK implementation of the JDK interface java.lang.Runnable. Used to exercise go-to-definition
 *  on a call made through the framework Runnable type (whose declaration isn't in the APK): gd must
 *  resolve to this override rather than failing. */
public class Job implements Runnable {
    @Override
    public void run() {
        System.out.println("job ran");
    }
}
