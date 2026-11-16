package org.marshive;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PlayerNamePolicyTest {
    private final PlayerNamePolicy names = new PlayerNamePolicy(SensitiveWordFilter.bundled());

    @Test public void acceptsSixChineseOrTwelveAsciiAndMixedNamesAtTheBoundary() {
        for (String name : new String[]{"阳光开心朋友", "Abc123_- xy!", "阳光好友AB12", "中abcdefghij"}) {
            assertEquals(name, names.playerName(name));
            assertEquals(name, names.roomName(name));
            assertEquals("[VS]" + name, names.roomName("[VS]" + name));
            assertEquals("[COOP]" + name, names.roomName("[COOP]" + name));
        }
    }

    @Test public void truncatesChineseAsciiAndMixedNamesAndCountsWhitespace() {
        String[][] cases = {
                {"阳光开心好朋友", "阳光开心好朋"}, {"abcdefghijklm", "abcdefghijkl"},
                {"阳光好友AB123", "阳光好友AB12"}, {"阳光开心朋友 ", "阳光开心朋友"},
                {" abcdefghijkl", " abcdefghijk"}, {"中abcdefghijk", "中abcdefghij"},
                {"abcdefghijk中Z", "abcdefghijk"}, {"a".repeat(255), "a".repeat(12)}
        };
        for (String[] sample : cases) {
            assertEquals(sample[1], names.playerName(sample[0]));
            assertEquals("[VS]" + sample[1], names.roomName("[VS]" + sample[0]));
            assertEquals("[COOP]" + sample[1], names.roomName("[COOP]" + sample[0]));
        }
    }

    @Test public void fullWidthAndSupplementaryCharactersCostTwoUnitsWithoutSplittingSurrogates() {
        for (String point : new String[]{"Ａ", "😊", "𠮷"}) {
            assertEquals(point.repeat(6), names.playerName(point.repeat(6)));
            assertEquals(point.repeat(6), names.playerName(point.repeat(7)));
        }
        assertEquals("e\u0301".repeat(4), names.playerName("e\u0301".repeat(5)));
    }

    @Test public void doesNotLeavePartialCombiningCharactersOrEmojiSequences() {
        assertEquals("abcdefghij", names.playerName("abcdefghije\u0301Z"));
        assertEquals("abcd👩🏽‍💻", names.playerName("abcd👩🏽‍💻Z"));
        assertEquals("abcde", names.playerName("abcde👩🏽‍💻Z"));
        assertEquals("[VS]abcde", names.roomName("[VS]abcde👩🏽‍💻Z"));
    }

    @Test public void onlyOneExactRoomPrefixIsExemptAndPlayerPrefixesStillCount() {
        assertEquals("[VS]abcdefghijkl", names.roomName("[VS]abcdefghijklm"));
        assertEquals("[VS]abcdefgh", names.playerName("[VS]abcdefghijkl"));
        assertEquals("[COOP]abcdef", names.playerName("[COOP]abcdefghijkl"));
        assertEquals("[VS][VS]abcdefgh", names.roomName("[VS][VS]abcdefghijkl"));
        assertEquals("[COOP][VS]abcdefgh", names.roomName("[COOP][VS]abcdefghijkl"));
        assertEquals("[vs]abcdefgh", names.roomName("[vs]abcdefghijkl"));
        assertEquals(" [VS]abcdefg", names.roomName(" [VS]abcdefghijkl"));
    }

    @Test public void skipsInvalidCharactersAndMatchesTheWholeNameBeforeClipping() throws Exception {
        PlayerNamePolicy policy = new PlayerNamePolicy(new SensitiveWordFilter(List.of("VS", "COOP", "坏词")));
        assertEquals("", policy.playerName(null));
        assertEquals("", policy.playerName("\uD800\u0000\uDC00"));
        assertEquals("badname", policy.playerName("b\u2028ad\nna\u2029me"));
        assertEquals("[VS]1234567890*", policy.roomName("[VS]1234567890坏\u200B词更多"));
        assertEquals("[COOP]**", policy.roomName("[COOP]坏\uD800\n词"));
        assertEquals("[**]**", policy.playerName("[VS]坏词"));
        assertEquals("a(b", PlayerNamePolicy.decode(new byte[]{'a', (byte) 0xc3, '(', 'b', (byte) 0xf0}));
        assertEquals("abcdefghijkl", policy.playerName("abcdefghijkl\n\uD800"));
        RoomManager rooms = new RoomManager(0, new SensitiveWordFilter(List.of("坏")));
        Room room = rooms.createRoom("[VS]" + "坏".repeat(7), null, 3215);
        assertEquals(1, rooms.roomCount());
        assertEquals(1001, room.getId());
        assertEquals("[VS]******", room.getName());
    }
}
