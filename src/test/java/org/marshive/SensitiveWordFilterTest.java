package org.marshive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.Assert.*;

public class SensitiveWordFilterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String mask(SensitiveWordFilter filter, String value) {
        return filter.mask(value, Integer.MAX_VALUE);
    }

    @Test public void masksChineseFullWidthCaseAndOverlappingTerms() throws Exception {
        SensitiveWordFilter filter = new SensitiveWordFilter(List.of("test", "坏词", "甲乙丙", "乙丙丁戊"));
        assertEquals("[VS] **房 ****", mask(filter, "[VS] 坏词房 ＴｅＳＴ"));
        assertEquals("*****", mask(filter, "甲乙丙丁戊"));
        assertEquals("****-****", mask(filter, "test-TEST"));
        assertEquals("[COOP] 阳光😀 123", mask(filter, "[COOP] 阳光😀 123"));
    }

    @Test public void normalizationMasksWholeTextElementsWithoutDamagingOtherEmoji() throws Exception {
        SensitiveWordFilter filter = new SensitiveWordFilter(List.of("é", "ss", "σ", "ffi", "👩🏽‍💻"));
        assertEquals("*房", mask(filter, "e\u0301房"));
        assertEquals("*房", mask(filter, "ß房"));
        assertEquals("***", mask(filter, "Σσς"));
        assertEquals("*房", mask(filter, "\uFB03房"));
        assertEquals("👨‍👩‍👧‍👦 *", mask(filter, "👨‍👩‍👧‍👦 👩🏽‍💻"));
        String masked = mask(filter, "[VS] 👩🏽‍💻 é");
        assertTrue(masked.getBytes(StandardCharsets.UTF_8).length <= "[VS] 👩🏽‍💻 é".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test public void bundledDictionaryIsUsableWithoutExternalFiles() {
        SensitiveWordFilter filter = SensitiveWordFilter.bundled();
        assertTrue(filter.wordCount() > 100);
        assertEquals("对战**站", mask(filter, "对战资源站"));
        assertEquals("[VS] **房间", mask(filter, "[VS] Ｑｑ房间"));
        assertEquals("***房", mask(filter, "制作组房"));
        assertEquals("[VS]****", mask(filter, "[VS]ＤｉＣｋ"));
        assertEquals("**** **** ***** ***", mask(filter, "ShIt FUCK PuSsY ass"));
        assertEquals("***** ******", mask(filter, "NiGgA NIGGER"));
        assertEquals("******* ************", mask(filter, "ASSHOLE motherfucker"));
        assertNotNull(SensitiveWordFilter.class.getResource("/sensitive_words/LICENSE"));
    }

    @Test public void loadsAllUtf8FilesWithBomCommasAndLinesAndDeduplicates() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Files.writeString(directory.resolve("one.txt"), "\uFEFF坏词,TEST\r\n坏词\n");
        Files.writeString(directory.resolve("two.txt"), "test\n第二词");
        Files.writeString(directory.resolve("ignored.md"), "普通");
        SensitiveWordFilter filter = SensitiveWordFilter.fromDirectory(directory);
        assertEquals(3, filter.wordCount());
        assertEquals("** **** *** 普通", mask(filter, "坏词 test 第二词 普通"));
        assertEquals("contest", mask(filter, "contest"));
    }

    @Test public void rejectsMissingEmptyAndMalformedDictionaries() throws Exception {
        Path directory = temporary.newFolder().toPath();
        assertThrows(IOException.class, () -> SensitiveWordFilter.fromDirectory(directory.resolve("missing")));
        assertThrows(IOException.class, () -> SensitiveWordFilter.fromDirectory(directory));
        Path words = directory.resolve("words.txt");
        Files.writeString(words, "\uFEFF ,\n\r\n");
        assertThrows(IOException.class, () -> SensitiveWordFilter.fromDirectory(directory));
        Files.write(words, new byte[]{(byte) 0xC3, 0x28});
        assertThrows(IOException.class, () -> SensitiveWordFilter.fromDirectory(directory));
    }

    @Test public void boundsFilesWordsAndTrieGrowth() throws Exception {
        Path directory = temporary.newFolder().toPath();
        for (int i = 0; i < 17; i++) Files.writeString(directory.resolve(i + ".txt"), "word");
        assertThrows(IOException.class, () -> SensitiveWordFilter.fromDirectory(directory));
        Path large = temporary.newFolder().toPath();
        Files.write(large.resolve("large.txt"), new byte[1024 * 1024 + 1]);
        assertThrows(IOException.class, () -> SensitiveWordFilter.fromDirectory(large));
        assertThrows(IOException.class, () -> new SensitiveWordFilter(List.of("x".repeat(129))));
        assertThrows(IOException.class, () -> new SensitiveWordFilter(List.of("\uFDFA".repeat(128))));
        assertThrows(IOException.class, () -> new SensitiveWordFilter(
                IntStream.range(0, 20001).mapToObj(i -> "word" + i).toList()));
        assertThrows(IOException.class, () -> new SensitiveWordFilter(
                IntStream.range(0, 2000).mapToObj(i -> i + "-" + "x".repeat(120)).toList()));
    }

    @Test public void manyOverlappingMatchesAndMaximumProtocolLengthStayBounded() throws Exception {
        SensitiveWordFilter filter = new SensitiveWordFilter(IntStream.rangeClosed(1, 128)
                .mapToObj(i -> "a".repeat(i)).toList());
        assertEquals("a".repeat(255), mask(filter, "a".repeat(255)));
        assertEquals("*".repeat(128), mask(filter, "a".repeat(128)));
        assertEquals("* ".repeat(127) + "*", mask(filter, "a ".repeat(127) + "a"));
    }

    @Test public void englishWordsDoNotMaskPartsOfNormalNames() {
        SensitiveWordFilter filter = SensitiveWordFilter.bundled();
        for (String name : new String[]{"parsee", "Parsee", "Ｐａｒｓｅｅ", "class", "Cassie", "Scunthorpe"}) {
            assertEquals(name, mask(filter, name));
        }
        assertEquals("**** ***", mask(filter, "ARSE ass"));
        assertEquals("***_****-****123", mask(filter, "ass_arse-DICK123"));
        assertEquals("玩家****", mask(filter, "玩家arse"));
        assertEquals("********", mask(filter, "arsehole"));
    }

    @Test public void boundariesUseCompleteNormalizedInputBeforeTruncation() throws Exception {
        SensitiveWordFilter filter = new SensitiveWordFilter(List.of("arse", "ass", "坏词"));
        assertEquals("parse", filter.mask("parsee", 5));
        assertEquals("ar", filter.mask("arsenal", 2));
        assertEquals("**", filter.mask("arse!", 2));
        assertEquals("éarse arseé", mask(filter, "éarse arseé"));
        assertEquals("e\u0301arse arse\u0301", mask(filter, "e\u0301arse arse\u0301"));
        assertEquals("****", mask(filter, "a\u200Drse"));
        assertEquals("你好**呀", mask(filter, "你好坏词呀"));
        PlayerNamePolicy policy = new PlayerNamePolicy(filter);
        assertEquals("[VS]parsee", policy.roomName("[VS]parsee"));
        assertEquals("parsee", policy.playerName("parsee"));
        assertEquals("[VS]****", policy.roomName("[VS]ARSE"));
    }
}
