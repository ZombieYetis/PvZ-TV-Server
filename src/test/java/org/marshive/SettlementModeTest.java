package org.marshive;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettlementModeTest {
    @Test public void newClientsDeriveFeaturesFromModeWithoutUploadingSwitches() {
        for (String mode : new String[]{"NORMAL", "SHUFFLE", "CLASSIC"}) {
            AnalyticsCollector.SettlementMeta meta = AnalyticsCollector.parseMeta(
                    "bg=0&battle=10&game_mode=" + mode + "&addon_ep=1&addon_ban=1");
            assertEquals(mode, meta.gameMode);
            assertEquals("SHUFFLE".equals(mode), meta.shuffleMode);
            assertEquals(!"CLASSIC".equals(mode), meta.addonExtraSeeds);
            assertEquals(!"CLASSIC".equals(mode), meta.addonBalancePatch);
            assertTrue(meta.addonExtraPackets);
            assertTrue(meta.addonBanMode);
        }
    }

    @Test public void explicitModeOverridesConflictingLegacyFlagsInEitherOrder() {
        for (String mode : new String[]{"normal", "shuffle", "classic"}) {
            for (String flags : new String[]{"addon_es=0&addon_bp=0&shuffle=0", "addon_es=1&addon_bp=1&shuffle=1"}) {
                for (String raw : new String[]{"game_mode=" + mode + "&" + flags, flags + "&game_mode=" + mode}) {
                    AnalyticsCollector.SettlementMeta meta = AnalyticsCollector.parseMeta(raw);
                    assertEquals(!"classic".equals(mode), meta.addonExtraSeeds);
                    assertEquals(!"classic".equals(mode), meta.addonBalancePatch);
                    assertEquals("shuffle".equals(mode), meta.shuffleMode);
                }
            }
        }
    }

    @Test public void legacyUploadsKeepIndependentSwitches() {
        for (int seeds = 0; seeds <= 1; seeds++) {
            for (int balance = 0; balance <= 1; balance++) {
                AnalyticsCollector.SettlementMeta meta = AnalyticsCollector.parseMeta(
                        "addon_es=" + seeds + "&addon_bp=" + balance);
                assertEquals("NORMAL", meta.gameMode);
                assertEquals(seeds == 1, meta.addonExtraSeeds);
                assertEquals(balance == 1, meta.addonBalancePatch);
            }
        }
        assertEquals("SHUFFLE", AnalyticsCollector.parseMeta("shuffle=true").gameMode);
        assertEquals("NORMAL", AnalyticsCollector.parseMeta("").gameMode);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidExplicitModeIsRejected() {
        AnalyticsCollector.parseMeta("game_mode=INVALID");
    }
}
