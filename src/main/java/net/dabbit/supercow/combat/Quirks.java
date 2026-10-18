package net.dabbit.supercow.combat;

import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.config.SuperCowConfig;

/**
 * Decides, per skill cast, whether the "fixed" behaviour or the original plugin's buggy behaviour is used.
 *
 * quirkMode:
 *  - "safe"  : every skill uses the repaired logic (the port as it was before the legacy roster came back)
 *  - "mixed" : each cast rolls quirkChaosChance; both flavours show up over time (default)
 *  - "chaos" : every skill uses the original buggy variant
 *
 * legacyQuirksEnabled=false forces "safe" regardless of the mode.
 */
public final class Quirks {
    public static final String SAFE = "safe";
    public static final String MIXED = "mixed";
    public static final String CHAOS = "chaos";

    private Quirks() {
    }

    public static String mode(SuperCowConfig cfg) {
        String raw = cfg.quirkMode == null ? "" : cfg.quirkMode.trim().toLowerCase();
        if (raw.startsWith("s") || raw.startsWith("correct") || raw.startsWith("pure") || raw.startsWith("fix")) {
            return SAFE;
        }
        if (raw.startsWith("c") || raw.startsWith("all") || raw.startsWith("bug")) {
            return CHAOS;
        }
        return MIXED;
    }

    public static String mode() {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        if (!cfg.legacyQuirksEnabled) {
            return SAFE;
        }
        return mode(cfg);
    }

    /** @return true when this cast should behave like the buggy original. */
    public static boolean roll() {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        if (!cfg.legacyQuirksEnabled) {
            return false;
        }
        String m = mode(cfg);
        if (SAFE.equals(m)) {
            return false;
        }
        if (CHAOS.equals(m)) {
            return true;
        }
        double chance = cfg.quirkChaosChance;
        if (chance < 0.0D) {
            chance = 0.0D;
        } else if (chance > 1.0D) {
            chance = 1.0D;
        }
        return Math.random() < chance;
    }

    public static String describe() {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        if (!cfg.legacyQuirksEnabled) {
            return "safe (legacyQuirksEnabled=false)";
        }
        String m = mode(cfg);
        if (MIXED.equals(m)) {
            return "mixed " + Math.round(cfg.quirkChaosChance * 100.0D) + "%";
        }
        return m;
    }
}
