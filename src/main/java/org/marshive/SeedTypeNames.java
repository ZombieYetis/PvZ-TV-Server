package org.marshive;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

final class SeedTypeNames {
    private static final Map<Integer, String> KEYS = new HashMap<>();
    private static final Map<String, String> ZH = new HashMap<>();

    static {
        put(0, "PEASHOOTER");
        put(1, "SUNFLOWER");
        put(2, "CHERRY_BOMB");
        put(3, "WALL_NUT");
        put(4, "POTATO_MINE");
        put(5, "SNOW_PEA");
        put(6, "CHOMPER");
        put(7, "REPEATER");
        put(8, "PUFF_SHROOM");
        put(9, "SUN_SHROOM");
        put(10, "FUME_SHROOM");
        put(11, "GRAVE_BUSTER");
        put(12, "HYPNO_SHROOM");
        put(13, "SCAREDY_SHROOM");
        put(14, "ICE_SHROOM");
        put(15, "DOOM_SHROOM");
        put(16, "LILY_PAD");
        put(17, "SQUASH");
        put(18, "THREEPEATER");
        put(19, "TANGLE_KELP");
        put(20, "JALAPENO");
        put(21, "SPIKEWEED");
        put(22, "TORCHWOOD");
        put(23, "TALL_NUT");
        put(24, "SEA_SHROOM");
        put(25, "PLANTERN");
        put(26, "CACTUS");
        put(27, "BLOVER");
        put(28, "SPLIT_PEA");
        put(29, "STARFRUIT");
        put(30, "PUMPKIN");
        put(31, "MAGNET_SHROOM");
        put(32, "CABBAGE_PULT");
        put(33, "FLOWER_POT");
        put(34, "KERNEL_PULT");
        put(35, "COFFEE_BEAN");
        put(36, "GARLIC");
        put(37, "UMBRELLA_LEAF");
        put(38, "MARIGOLD");
        put(39, "MELON_PULT");
        put(40, "GATLING_PEA");
        put(41, "TWIN_SUNFLOWER");
        put(42, "GLOOM_SHROOM");
        put(43, "CATTAIL");
        put(44, "WINTER_MELON");
        put(45, "GOLD_MAGNET");
        put(46, "SPIKEROCK");
        put(47, "COB_CANNON");
        put(48, "IMITATER");
        put(50, "EXPLODE_O_NUT");
        put(51, "GIANT_WALLNUT");
        put(52, "SPROUT");
        put(53, "REPEATER");
        put(55, "BEGHOULED_SHUFFLE");
        put(56, "BEGHOULED_CRATER");
        put(57, "SLOT_MACHINE_SUN");
        put(58, "SLOT_MACHINE_DIAMOND");
        put(59, "ZOMBIQUARIUM_SNORKLE");
        put(60, "ZOMBIQUARIUM_TROPHY");
        put(61, "ZOMBIE_GRAVESTONE");
        put(62, "ZOMBIE");
        put(63, "TRASHCAN_ZOMBIE");
        put(64, "CONEHEAD_ZOMBIE");
        put(65, "POLE_VAULTING_ZOMBIE");
        put(66, "BUCKETHEAD_ZOMBIE");
        put(67, "FLAG_ZOMBIE");
        put(68, "NEWSPAPER_ZOMBIE");
        put(69, "SCREEN_DOOR_ZOMBIE");
        put(70, "FOOTBALL_ZOMBIE");
        put(71, "DANCING_ZOMBIE");
        put(72, "ZOMBONI");
        put(73, "JACK_IN_THE_BOX_ZOMBIE");
        put(74, "DIGGER_ZOMBIE");
        put(75, "POGO_ZOMBIE");
        put(76, "BUNGEE_ZOMBIE");
        put(77, "LADDER_ZOMBIE");
        put(78, "CATAPULT_ZOMBIE");
        put(79, "GARGANTUAR");
        put(80, "ZOMBIE_YETI");
        put(81, "ZOMBIE_BOBSLED_TEAM");
        put(82, "SNORKEL_ZOMBIE");
        put(83, "DOLPHIN_RIDER_ZOMBIE");
        put(84, "IMP");
        put(85, "BALLOON_ZOMBIE");
        put(86, "PEA_HEAD_ZOMBIE");
        put(87, "JALAPENO_HEAD_ZOMBIE");
        put(88, "GATLING_HEAD_ZOMBIE");
        put(89, "SQUASH_HEAD_ZOMBIE");
        put(90, "TALLNUT_HEAD_ZOMBIE");
        put(91, "ZOMBIE_MOUND");
        put(92, "GIGA_FOOTBALL_ZOMBIE");
        put(93, "SUPER_FAN_IMP");
        put(94, "JACKSON_ZOMBIE");
        put(95, "GIGA_POLE_VAULTING_ZOMBIE");
        put(96, "WALLNUT_HEAD_ZOMBIE");
        put(97, "BOSS");
        put(98, "REDEYED_GARGANTUAR");
        put(100, "ZOMBIE_BEGHOULED_SHUFFLE");

        loadLocalizedStrings("LawnStrings.txt");
        loadLocalizedStrings("AddonStrings.txt");
        loadLocalizedStrings("target" + File.separator + "LawnStrings.txt");
        loadLocalizedStrings("target" + File.separator + "AddonStrings.txt");
        loadLocalizedStrings("data" + File.separator + "LawnStrings.txt");
        loadLocalizedStrings("data" + File.separator + "AddonStrings.txt");
        System.out.println("[SEED_I18N] loaded zh entries: " + ZH.size());
    }

    private SeedTypeNames() {
    }

    static String nameOf(int seedType) {
        String key = KEYS.get(seedType);
        return key == null ? "UnknownSeed" : key;
    }

    static String zhNameOf(int seedType) {
        String key = nameOf(seedType);
        String zh = ZH.get(key);
        return (zh == null || zh.trim().isEmpty()) ? key : zh.trim();
    }

    private static void put(int id, String key) {
        KEYS.put(id, key);
    }

    private static void loadLocalizedStrings(String path) {
        File f = new File(path);
        if (!f.exists()) return;
        int before = ZH.size();
        String[] charsets = new String[]{"UTF-8", "GBK", "UTF-16LE", "UTF-16"};
        for (String cs : charsets) {
            if (tryLoad(f, cs)) {
                int loaded = ZH.size() - before;
                if (loaded > 0) {
                    System.out.println("[SEED_I18N] loaded " + loaded + " from " + f.getPath() + " (" + cs + ")");
                    return;
                }
            }
        }
    }

    private static boolean tryLoad(File f, String charset) {
        int before = ZH.size();
        Set<String> touched = new LinkedHashSet<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), charset))) {
            String currentKey = null;
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("[") && t.endsWith("]") && t.length() > 2) {
                    currentKey = t.substring(1, t.length() - 1).trim();
                    continue;
                }
                if (currentKey != null && !t.startsWith("[") && !t.endsWith("]")) {
                    ZH.put(currentKey, t);
                    touched.add(currentKey);
                    currentKey = null;
                }
            }
            return ZH.size() > before;
        } catch (Exception ignored) {
            return false;
        }
    }
}
