package org.marshive;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class FailureRecoveryTest {
    private void exitsWithFailure(String mode, int expectedCode) throws Exception {
        File log = File.createTempFile("pvz-failure-", ".log");
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        Process process = new ProcessBuilder(java, "-Xmx32m", "-XX:+ExitOnOutOfMemoryError", "-cp",
                System.getProperty("java.class.path"), FailureProbe.class.getName(), mode)
                .redirectErrorStream(true).redirectOutput(log).start();
        try {
            assertTrue("child failed to exit: " + mode, process.waitFor(15, TimeUnit.SECONDS));
            String output = Files.readString(log.toPath());
            assertNotEquals(output, 0, process.exitValue());
            if (expectedCode > 0) assertEquals(output, expectedCode, process.exitValue());
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(log.toPath());
        }
    }

    @Test public void uncaughtThreadFailureTerminatesTheWholeProcess() throws Exception { exitsWithFailure("uncaught", 1); }
    @Test public void stalledGameLoopTerminatesTheWholeProcess() throws Exception { exitsWithFailure("watchdog", 2); }
    @Test public void oomFlagTerminatesTheWholeProcess() throws Exception { exitsWithFailure("oom", -1); }
}
