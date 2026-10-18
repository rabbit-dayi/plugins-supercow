package net.dabbit.supercow.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Conservative numerical configuration for SuperCow (1.16.5 Fabric).
 * All values are tuned conservatively to prevent server lag, terrain griefing,
 * entity spam, or overpowered combat scaling.
 */
public class SuperCowConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Taming & Pet progression (conservative)
    public double tameChance = 0.35;
    public int maxLevel = 20;
    public double baseHealth = 60.0;
    public double healthPerLevel = 10.0;
    public double regenAmount = 2.0; // HP restored per regen tick (original plugin behaviour)
    public int regenIntervalTicks = 100; // 5s, matches the original Bukkit implementation
    public double baseSpeed = 0.25;
    public double speedPerLevel = 0.003;
    public double maxSpeed = 0.32;
    public double baseDamage = 3.0;
    public double damagePerLevel = 0.3;

    // Feeding & Revival (conservative)
    public double hayHealAmount = 4.0;
    public int hayExpReward = 15;
    public int feedCooldownTicks = 100; // 5s
    public int naturalReviveSeconds = 120; // 2m
    public int baseExpNeeded = 100;
    public int expPerLevelStep = 60;

    // Combat core (conservative)
    public int attackCooldownTicks = 40; // 2.0s (was 1.0s)
    public int specialAttackCooldownTicks = 120; // 6.0s
    public double specialAttackChance = 0.15; // 15% (was 30%)
    public double rageSpecialAttackChance = 0.25; // 25% (was 60%)
    public double closeRange = 4.0;
    public double mediumRange = 8.0;
    public double farRange = 14.0;
    public double maxTargetDistance = 16.0;
    public double teleportToOwnerDistance = 24.0;

    // Combat orchestration (conservative)
    public double closeRangeDistance = 3.0; // melee distance
    public double mediumRangeDistance = 9.0; // special-attack mid band
    public double followDistance = 4.0;
    public double teleportDistance = 24.0;
    public double detectionRange = 10.0; // AGGRESSIVE auto-acquire
    public double protectRange = 6.0; // PROTECTING auto-acquire
    public int rageAttackCooldownTicks = 25; // 1.25s
    public double critChance = 0.15;
    public double critMultiplier = 1.5;
    public int expPerKill = 10;

    // Rage mode (conservative)
    public double rageHealthThreshold = 0.25; // 25% HP (was 30%)
    public int rageDurationTicks = 160; // 8s (was 15s)
    public int rageCooldownTicks = 900; // 45s (was 30s)
    public double rageDamageMultiplier = 1.25; // +25%

    // Arrow attack (conservative)
    public int normalArrowCount = 1; // was 3
    public int rageArrowCount = 2; // was 5
    public double normalArrowDamage = 2.5; // was 6.0
    public double rageArrowDamage = 3.5; // was 9.0
    public double arrowEffectChance = 0.35; // 35% (was 100%)
    public double arrowSpeed = 1.6;
    public double arrowSpread = 4.0;

    // Arrow special effects (conservative)
    public int frostDurationTicks = 40; // 2s (was 5s)
    public double frostBonusDamage = 1.0; // was 2.0
    public double lightningDamage = 2.0; // was 8.0
    public double rageLightningDamage = 3.0; // was 14.0
    public double explosionArrowRadius = 2.0; // was 4.0
    public double explosionArrowDamage = 2.0; // was 6.0
    public double vortexRadius = 2.5; // was 5.0
    public double vortexPullStrength = 0.25; // was 0.8
    public double vortexDamage = 1.0; // was 4.0
    public int rainArrowCount = 4; // was 12
    public double rainArrowDamage = 1.5; // was 4.0
    public double rainRadius = 2.5; // was 4.0
    public int bounceMaxCount = 2; // was 5
    public double bounceRange = 4.0; // was 8.0
    public double bounceDamageDecay = 0.75;

    // Fireball & Potion (conservative)
    public double fireballDamage = 3.0; // was 5.0
    public double rageFireballDamage = 4.5; // was 8.0
    public int fireballFireTicks = 40; // 2s (was 5s-10s)
    public int rageFireballFireTicks = 60; // 3s
    public double potionSplashRadius = 2.5; // was 4.0-6.0
    public double ragePotionSplashRadius = 3.5;
    public double potionDirectDamage = 1.5;
    public double ragePotionDirectDamage = 2.5;
    public int potionEffectDurationTicks = 60; // 3s (was 10s-15s)

    // Firework attack (conservative)
    public int normalFireworkCount = 1; // was 3
    public int rageFireworkCount = 2; // was 5
    public double fireworkDamage = 3.0; // was 8.0
    public double rageFireworkDamage = 4.5; // was 15.0
    public double fireworkRadius = 2.5; // was 4.0-6.0
    public double rageFireworkRadius = 3.5;
    public double fireworkMaxHeight = 6.0; // was 15.0

    // Chicken bombardment (conservative)
    public int normalChickenCount = 2; // was 5
    public int rageChickenCount = 3; // was 8
    public double chickenHeightOffset = 5.0; // was 10.0
    public int bombardmentDurationTicks = 80; // 4s (was 10s)
    public int eggDropIntervalTicks = 20; // 1.0s (was 0.25s)
    public double eggExplosionDamage = 2.0; // was 5.0
    public double rageEggExplosionDamage = 3.0; // was 8.0
    public double eggExplosionRadius = 2.0; // was 3.0
    public int eggSlownessTicks = 40; // 2s

    // Music attack (conservative)
    public int musicDurationTicks = 60; // 3s (was 5s)
    public int musicTickInterval = 15; // 0.75s (was 0.25s)
    public double musicRadius = 4.0; // was 8.0
    public double musicDamage = 1.5; // was 4.0
    public double rageMusicDamage = 2.2; // was 7.0
    public double musicKnockback = 0.25; // was 1.2
    public int musicDebuffTicks = 40; // 2s

    // Exploding cow (conservative, fixed 5-tick lifetime bug)
    public double explodingCowDamage = 4.0; // was 15.0
    public double rageExplodingCowDamage = 6.0; // was 25.0
    public double explodingCowRadius = 2.5; // was 5.0-8.0
    public double rageExplodingCowRadius = 3.5;
    public double explodingCowKnockback = 0.4; // was 1.5-2.5
    public double explodingCowLaunchSpeed = 0.9; // was 1.5
    public int explodingCowFuseTicks = 30; // 1.5s
    public int explodingCowMaxLifetimeTicks = 60; // 3.0s (was 5 ticks!)

    // Summoned cows (conservative, fixed 1-tick cooldown & hay bale consumption)
    public int normalSummonCount = 2; // was 6
    public int rageSummonCount = 3; // was 10
    public int summonLifetimeTicks = 400; // 20s (was 60s)
    public int summonCooldownTicks = 600; // 30s
    public double summonHealth = 14.0; // was 30.0
    public double rageSummonHealth = 18.0; // was 50.0
    public int summonAttackCooldownTicks = 40; // 2.0s (was 1 tick!)
    public double summonArrowDamage = 1.5; // was 4.0
    public double rageSummonArrowDamage = 2.2; // was 7.0
    public double summonFollowDistance = 3.0;
    public double summonTargetRange = 10.0;
    public boolean consumeHayBaleForSummon = true;

    // ---------------------------------------------------------------------------------
    // Legacy roster restored from the original Bukkit plugin (m01244)
    // ---------------------------------------------------------------------------------
    public boolean legacySkillsEnabled = true;   // original weighted skill table instead of distance pools
    public boolean legacyQuirksEnabled = true;   // the original "bugs" are kept on purpose, as skills
    public boolean legacyMovementEnabled = true; // velocity-based "abstract" pathfinding for the pet

    // Parrot / rabbit summons (original summon_parrots / summon_rabbits)
    public int parrotSummonCount = 1;      // was 2
    public int rageParrotSummonCount = 2;  // was 5
    public int rabbitSummonCount = 2;      // was 3
    public int rageRabbitSummonCount = 3;  // was 8
    public int birdieLifetimeTicks = 300;  // 15s
    public double parrotPeckDamage = 0.75; // was 2.0
    public double rabbitBiteDamage = 1.0;  // was 3.0
    public int minionAttackCooldownTicks = 40; // 2.0s per minion

    // Quirks kept as skills
    public double explodingCowPotionBugChance = 0.2; // original fired a potion from the exploding-cow branch
    public double arrowHealAmount = 1.0;             // every arrow volley healed the pet (original 2.5)
    public boolean musicInvertedTargeting = true;    // debuffs everyone EXCEPT the target
    public boolean musicZeroKnockback = true;        // original knockback vector was always zero
    public boolean eggHitsAnyNonFriendly = true;     // eggs may hit other players, never the owner's party

    // Legacy movement tuning (original: OPTIMAL_COMBAT_DISTANCE 8.0, MAX_SPEED 0.6, STRAFE 0.4)
    public double legacyMoveSpeed = 0.42;
    public double legacyStrafeStrength = 0.16;
    public double legacyJumpPower = 0.42;
    public double legacyOptimalDistance = 8.0;
    public double legacyFollowStopDistance = 2.0;

    public static SuperCowConfig load(File file) {
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                SuperCowConfig loaded = GSON.fromJson(reader, SuperCowConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        SuperCowConfig config = new SuperCowConfig();
        config.save(file);
        return config;
    }

    public void save(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
