package org.marshive;

/** Matching-only transformations. Never use these forms as a player's display name. */
final class NameMatchText {
    private NameMatchText() {}

    static String withoutIgnorables(String value) {
        StringBuilder result = null;
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            if (isIgnorable(point)) {
                if (result == null) result = new StringBuilder(value.length()).append(value, 0, offset);
            } else if (result != null) {
                result.appendCodePoint(point);
            }
            offset += Character.charCount(point);
        }
        return result == null ? value : result.toString();
    }

    // Unicode 17 Default_Ignorable_Code_Point, including supplementary variation selectors.
    // https://www.unicode.org/Public/17.0.0/ucd/DerivedCoreProperties.txt
    // Keep visible accents, vowel marks, whitespace, and emoji modifiers out of this set.
    private static boolean isIgnorable(int p) {
        return p == 0x00AD || p == 0x034F || p == 0x061C
                || p >= 0x115F && p <= 0x1160 || p >= 0x17B4 && p <= 0x17B5
                || p >= 0x180B && p <= 0x180F || p >= 0x200B && p <= 0x200F
                || p >= 0x202A && p <= 0x202E || p >= 0x2060 && p <= 0x206F
                || p == 0x3164 || p >= 0xFE00 && p <= 0xFE0F || p == 0xFEFF
                || p == 0xFFA0 || p >= 0xFFF0 && p <= 0xFFF8
                || p >= 0x1BCA0 && p <= 0x1BCA3 || p >= 0x1D173 && p <= 0x1D17A
                || p >= 0xE0000 && p <= 0xE0FFF;
    }

    // Small, explicit one-to-one lookalike set, not general transliteration or fuzzy spelling.
    // Called after NFKC/lowercasing. SensitiveWordFilter limits when these matches may apply.
    static int latinLookalike(int point) {
        return switch (point) {
            case 'а', 'α', 'ɑ' -> 'a';
            case 'ь', 'в', 'β', 'ʙ' -> 'b';
            case 'с' -> 'c';
            case 'ԁ' -> 'd';
            case 'е', 'ε' -> 'e';
            case 'һ' -> 'h';
            case 'і', 'ι', 'ı' -> 'i';
            case 'ј' -> 'j';
            case 'к', 'κ' -> 'k';
            case 'ӏ' -> 'l';
            case 'м' -> 'm';
            case 'о', 'ο' -> 'o';
            case 'р', 'ρ' -> 'p';
            case 'ѕ' -> 's';
            case 'т', 'τ' -> 't';
            case 'υ' -> 'u';
            case 'ѵ', 'ν' -> 'v';
            case 'х', 'χ' -> 'x';
            case 'у' -> 'y';
            default -> point;
        };
    }
}
