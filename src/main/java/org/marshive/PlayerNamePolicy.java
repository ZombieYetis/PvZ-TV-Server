package org.marshive;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class PlayerNamePolicy {
    private static final int MAX_UNITS = 12;
    private static final String[] MODE_PREFIXES = {"[VS]", "[COOP]"};
    private final SensitiveWordFilter words;

    PlayerNamePolicy(SensitiveWordFilter words) {
        this.words = words;
    }

    static String decode(byte[] bytes) {
        // UTF-8 never needs more UTF-16 units than input bytes.
        CharBuffer text = CharBuffer.allocate(bytes.length);
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
                .decode(ByteBuffer.wrap(bytes), text, true);
        return text.flip().toString();
    }

    String roomName(String raw) {
        if (raw != null) {
            for (String prefix : MODE_PREFIXES) {
                if (raw.startsWith(prefix)) return prefix + playerName(raw.substring(prefix.length()));
            }
        }
        return playerName(raw);
    }

    String playerName(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int[] points = raw.codePoints().filter(PlayerNamePolicy::allowed).toArray();
        return words.mask(new String(points, 0, points.length), MAX_UNITS);
    }

    private static boolean allowed(int point) {
        return switch (Character.getType(point)) {
            case Character.FORMAT -> point == 0x200D; // Keep joined emoji intact.
            case Character.CONTROL, Character.SURROGATE, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        };
    }
}
