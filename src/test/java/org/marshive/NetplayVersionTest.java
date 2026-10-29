package org.marshive;

import org.junit.Test;
import static org.junit.Assert.*;

public class NetplayVersionTest {
    @Test public void absentVersionRemainsUnknown() {
        assertEquals(0, AnalyticsCollector.parseMeta("").netplayVersion);
        assertEquals(0, AnalyticsCollector.parseMeta("game_mode=NORMAL&bg=0").netplayVersion);
    }

    @Test public void storesNumericVersionsIncludingUnsignedIntRange() {
        for (String mode : new String[]{"NORMAL", "SHUFFLE", "CLASSIC"}) {
            assertEquals(3212, AnalyticsCollector.parseMeta("game_mode=" + mode + "&netplay_version=3212").netplayVersion);
        }
        assertEquals(4294967295L, AnalyticsCollector.parseMeta("netplay_version=4294967295").netplayVersion);
    }

    @Test public void invalidVersionCannotSilentlyBecomeUnversionedData() {
        for (String value : new String[]{"", "0", "-1", "+1", "3.2.12", "all", "4294967296", "99999999999999999999"}) {
            assertThrows(value, IllegalArgumentException.class,
                    () -> AnalyticsCollector.parseMeta("netplay_version=" + value));
        }
    }
}
