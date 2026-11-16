package org.marshive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SeedTypeNamesTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void loadsLocalizedNamesFromBundledGameStrings() {
        assertEquals("豌豆射手", SeedTypeNames.zhNameOf(0));
        assertEquals("交通协管僵尸", SeedTypeNames.zhNameOf(102));
        assertEquals("红针花", SeedTypeNames.zhNameOf(210));
        assertEquals("PIGEON_FEEDER_ZOMBIE", SeedTypeNames.nameOf(106));
        assertEquals("喂鸽僵尸", SeedTypeNames.zhNameOf(106));
        assertTrue(SeedTypeNames.isZombieSeed(106));
        assertTrue(!SeedTypeNames.isPlantSeed(106));
    }

    @Test
    public void externalCopiesOfBundledStringsKeepChineseNames() throws Exception {
        for (String location : new String[]{"", "target", "data"}) {
            Path directory = temporary.newFolder().toPath();
            Path stringsDirectory = Files.createDirectories(directory.resolve(location));
            for (String name : new String[]{"LawnStrings.txt", "AddonStrings.txt"}) {
                try (InputStream input = getClass().getResourceAsStream("/" + name)) {
                    assertNotNull(name, input);
                    Files.copy(input, stringsDirectory.resolve(name));
                }
            }
            assertNamesInFreshProcess(directory, "豌豆射手", "交通协管僵尸", "红针花");
        }
    }

    @Test
    public void utf8OverridesKeepChineseNamesAndExternalFilePrecedence() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Files.writeString(directory.resolve("LawnStrings.txt"),
                "[PEASHOOTER]\n根目录豌豆\n", StandardCharsets.UTF_8);
        Path target = Files.createDirectory(directory.resolve("target"));
        Files.writeString(target.resolve("LawnStrings.txt"),
                "\uFEFF[PEASHOOTER]\n目标目录豌豆\n", StandardCharsets.UTF_8);
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("LawnStrings.txt"),
                "[PEASHOOTER]\n自定义豌豆\n", StandardCharsets.UTF_8);
        Files.writeString(data.resolve("AddonStrings.txt"),
                "\uFEFF[RED_STINGER]\n自定义红针花\n", StandardCharsets.UTF_8);

        assertNamesInFreshProcess(directory, "自定义豌豆", "交通协管僵尸", "自定义红针花");
    }

    private void assertNamesInFreshProcess(Path directory, String pea, String crossingGuard,
                                          String redStinger) throws Exception {
        // A separate JVM exercises startup loading without sharing the static name cache.
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(path -> new File(path).getAbsolutePath()).collect(Collectors.joining(File.pathSeparator));
        Path log = directory.resolve("names.log");
        Process process = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-cp", classpath,
                NameProbe.class.getName(), pea, crossingGuard, redStinger)
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("name loader did not exit", process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(Files.readString(log), 0, process.exitValue());
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static final class NameProbe {
        public static void main(String[] args) {
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
            assertEquals(args[0], SeedTypeNames.zhNameOf(0));
            assertEquals(args[1], SeedTypeNames.zhNameOf(102));
            assertEquals(args[2], SeedTypeNames.zhNameOf(210));
        }
    }
}
