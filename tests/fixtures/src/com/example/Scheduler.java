package com.example;

/** Invokes run() through the framework Runnable type, so the call resolves (in jadx) to
 *  java.lang.Runnable.run — a declaration NOT in the APK. gd on that call must offer the APK
 *  implementation(s) (e.g. Job) instead of failing. */
public class Scheduler {
    public void go(Runnable task) {
        task.run();
    }
}
