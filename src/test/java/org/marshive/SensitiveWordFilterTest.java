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
        assertEquals("***", mask(filter, "b站找"));
        for (String word : List.of("b站搜", "B站搜", "b站查", "B站查")) {
            assertEquals("***", mask(filter, word));
        }
        assertEquals("************", mask(filter, "BV1b9yfzjEfD"));
        assertEquals("***************", mask(filter, "B站找bv1b9yfzjefd"));
        assertEquals("[VS] **房间", mask(filter, "[VS] Ｑｑ房间"));
        assertEquals("***房", mask(filter, "制作组房"));
        assertEquals("**", mask(filter, "沙比"));
        assertEquals("小**房", mask(filter, "小沙比房"));
        for (String word : List.of("傻逼", "煞笔", "傻比", "啥比", "傻笔", "啥币")) {
            assertEquals("小**房", mask(filter, "小" + word + "房"));
        }
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

    @Test public void sexualTermsTreatCaoVariantsEquallyWithoutBlockingOrdinaryWords() {
        SensitiveWordFilter filter = SensitiveWordFilter.bundled();
        for (String word : List.of("爆操", "被操", "操逼", "操黑", "操烂", "操死", "操s", "操S", "操我", "狂操", "轮操", "操你")) {
            for (char variant : new char[]{'草', '艹', '操', '肏'}) {
                assertEquals(word, "**", mask(filter, word.replace('操', variant)));
            }
        }
        for (String name : List.of("草坪", "小草", "操作", "曹操", "艹")) {
            assertEquals(name, name, mask(filter, name));
        }
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

    @Test public void invisibleCharactersCannotSplitBlockedWordsOrHideTheirVisiblePrefix() throws Exception {
        SensitiveWordFilter filter = new SensitiveWordFilter(List.of("b站找", "arse"));
        int[] invisible = {0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5,
                0x180B, 0x180D, 0x180F, 0x200B, 0x200C, 0x200D, 0x2060, 0x2065, 0x3164,
                0xFE00, 0xFE0E, 0xFE0F, 0xFEFF, 0xFFA0, 0xFFF0, 0x1BCA0, 0x1D173,
                0xE0000, 0xE0001, 0xE0020, 0xE007F, 0xE0100, 0xE01EF, 0xE0FFF};
        for (int point : invisible) {
            String hidden = new String(Character.toChars(point));
            for (String input : List.of("b" + hidden + "站找", "b站" + hidden + "找", "ar" + hidden + "se")) {
                String masked = mask(filter, input);
                assertTrue("Unmasked U+" + Integer.toHexString(point), masked.matches("\\*+"));
            }
        }
        // All standard and supplementary variation selectors, including non-emoji selectors.
        for (int point : IntStream.concat(IntStream.rangeClosed(0xFE00, 0xFE0F),
                IntStream.rangeClosed(0xE0100, 0xE01EF)).toArray()) {
            assertEquals("***", mask(filter, "b" + new String(Character.toChars(point)) + "站找"));
        }
        assertEquals("**", filter.mask("b\u034F站找", 5));
        PlayerNamePolicy policy = new PlayerNamePolicy(filter);
        assertEquals("[VS]***", policy.roomName("[VS]b\u034F站找"));
        assertEquals("***", policy.playerName("b\uFE00站找"));
    }

    @Test public void lookalikesMaskAdsAndMixedScriptProfanityButRespectWordBoundaries() {
        SensitiveWordFilter filter = SensitiveWordFilter.bundled();
        for (String letter : List.of("ь", "Ь", "в", "В", "β", "Β", "ʙ", "ｂ", "𝐛")) {
            for (String suffix : List.of("站找", "站搜", "站查")) {
                assertEquals("***", mask(filter, letter + suffix));
                assertEquals("***", mask(filter, letter + "\u034F" + suffix));
            }
        }
        assertEquals("************", mask(filter, "ВѴ1ь9уfzјЕfԁ"));
        assertEquals("****", mask(filter, "Dіck"));
        assertEquals("***", mask(filter, "аss"));
        assertEquals("****", mask(filter, "аr\u034Fse"));
        for (String name : List.of("pаrsee", "рarseе", "сlаss", "Cаssie", "Scunthоrpe", "ɑrsenal")) {
            assertEquals(name, mask(filter, name));
        }
        PlayerNamePolicy policy = new PlayerNamePolicy(filter);
        assertEquals("[VS]***", policy.roomName("[VS]ь\uFE00站找"));
        assertEquals("**", filter.mask("ь站找", 4));
        assertEquals("рars", filter.mask("рarseе", 5));
    }

    @Test public void normalForeignNamesAccentsEmojiAndSpacingRemainUnchanged() throws Exception {
        SensitiveWordFilter filter = new SensitiveWordFilter(List.of("b站找", "ass", "arse", "dick"));
        for (String name : List.of("Василий", "Борис", "Саша", "Νίκος", "асс", "і", "ь", "β",
                "Léa", "e\u0301arse", "éarse", "arseé", "👩🏽‍💻", "👨‍👩‍👧‍👦", "☀\uFE0F",
                "葛\uDB40\uDD00", "普通\u034F名字", "小草", "操作", "b 站找", "b-站找", "b\u0301站找")) {
            assertEquals(name, mask(filter, name));
        }
        Path directory = temporary.newFolder().toPath();
        Files.writeString(directory.resolve("广告.txt"), "b站找\n");
        assertEquals("***", mask(SensitiveWordFilter.fromDirectory(directory), "ь\u034F站找"));
    }
}
