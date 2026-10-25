package org.marshive;

/** Runs only in a child JVM so fatal-exit behavior cannot terminate the test runner. */
public class FailureProbe {
    public static void main(String[] args) throws Exception {
        ServerHealth.installFailureHandler();
        if ("watchdog".equals(args[0])) {
            System.setProperty("pvz.loopTimeoutSeconds", "5");
            ServerHealth.startWatchdog();
        } else if ("uncaught".equals(args[0])) {
            new Thread(() -> { throw new IllegalStateException("test critical thread failure"); }).start();
        } else if ("oom".equals(args[0])) {
            byte[] oversized = new byte[128 * 1024 * 1024];
            System.out.println(oversized.length);
        }
        Thread.sleep(60000);
    }
}
