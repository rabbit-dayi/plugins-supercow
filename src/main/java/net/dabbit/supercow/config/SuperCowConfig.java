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
    public double teleportToOwnerDistance = 32.0;

    // Combat orchestration (conservative)
    public double closeRangeDistance = 3.0; // melee distance
    public double mediumRangeDistance = 9.0; // special-attack mid band
    // 跟随站位：牛停在离主人这个距离的地方。原来 4 格贴得太近，牛走路时的蹄声会一直在耳边响；
    // 先试过 10 格，还是吵，默认改成 20 格（"远"档），可用 /supercow follow <2-24> 或面板第 7 格调。
    // 注意：这是"站位半径"，不是"触发移动的阈值"——多出来的余量由 followStartBuffer 控制，
    // 牛只在明显走出去之后才起步，走到 followDistance 附近就减速停下（见 PathfindingManager）。
    public double followDistance = 20.0;
    // 跟随的起步判定发生在"最优距离 + buffer"之外：默认 20 + 4 = 24 格起步追赶，超过 32 格再瞬移。
    public double followStartBuffer = 4.0;
    // 跟随减速带：离主人 followDistance*followChaseBand 以外全速，进入 followDistance 以内
    // （也就是最后 followBrakeDistance 格）平滑减速到停，牛就停在 followDistance 处。
    // 纯软件减速，不依赖 legacyOptimalDistance，所以任何跟随距离（2~24）都不用改这里。
    public double followChaseBand = 1.25;
    public double followBrakeDistance = 3.0;
    // 跟随时的移动倍率（PathfindingManager 里还会夹在 [0.35,1.5]）
    public double followMoveSpeed = 1.2;
    // 停止半径的下限（牛最多贴到 followDistance - followStopDistance 那么近，默认 20-1.5=18.5），
    // 主要作为 CombatManager 与面板显示的兜底语义：真正让牛停下的仍然是 followDistance 本身。
    public double followStopDistance = 1.5;
    // 兼容字段：1.5 / 10.0 时代的手工微调值，已不再参与任何计算（保留以免老配置文件反序列化失败）。
    public double followStopDistanceLegacy;
    public boolean petAmbientSoundEnabled = false; // 牛自己的哞哞声默认关掉，打架时太吵
    public double teleportDistance = 32.0;
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
    public String quirkMode = "mixed";           // safe | mixed | chaos  (correct behaviour vs original bugs)
    public double quirkChaosChance = 0.5;        // "mixed" mode: chance of using the buggy variant per cast
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
    public boolean eggQuirkPlayersOnly = true;       // original bug: eggs only damaged players (owner included)

    // Legacy movement tuning (original: OPTIMAL_COMBAT_DISTANCE 8.0, MAX_SPEED 0.6, STRAFE 0.4)
    public double legacyMoveSpeed = 0.42;
    public double legacyStrafeStrength = 0.16;
    // 追求"绕圈 Z 字"的抽象感只在战斗中保留；走路跟随时走直线，别让牛一直原地转圈。
    public boolean legacyStrafeOnlyInCombat = true;
    public double legacyTravelSpeed = 0.3;       // 非战斗行进的基准速度（比战斗冲刺慢一点，看着正常）
    public double legacyJumpPower = 0.42;
    // 只用于"战斗追击"：追怪时的最优交战半径（跟随主人不再使用这个值，见 followDistance）。
    public double legacyOptimalDistance = 8.0;
    public double legacyFollowStopDistance = 2.0;

    // 转圈飞天（原插件寻路可以把自己送上天）—— 作为小概率触发的技能保留
    public boolean skySpiralEnabled = true;      // 总开关
    public double skySpiralChance = 0.04;        // 每次锁定新目标时的触发概率（还要再乘 quirkMode 的掷骰）
    public int skySpiralDurationTicks = 70;      // 一次螺旋上升持续 3.5 秒
    public double skySpiralLift = 0.7;           // 每 tick 上升速度（会被原版重力衰减），0.7 ≈ 14 格/秒
    public double skySpiralMaxHeight = 22.0;     // 相对起点最多升高 22 格
    public double skySpiralSpinSpeed = 0.5;      // 每 tick 转多少弧度（转圈的手感）
    public int skySpiralCooldownTicks = 900;     // 一次飞天之后 45 秒内不再触发
    public boolean skySpiralLaunchesAway = true; // 起飞瞬间给一个大跳，真的是"猛"地冲上天
    public double skySpiralLaunchVelocity = 1.35; // 起飞初速度（1.35 ≈ 一次跳两格多）

    // ---------------------------------------------------------------------------------
    // 母牛箱子 GUI（右键宠物牛）与技能开关
    // ---------------------------------------------------------------------------------
    public boolean petGuiEnabled = true;         // 右键宠物牛打开箱子界面
    public boolean petGuiNeedsEmptyHand = false;  // 需要空手（拿干草块右键仍然是喂食）
    /** skillId -> 是否启用；缺省视为启用 */
    public java.util.Map<String, Boolean> skillEnabled = new java.util.LinkedHashMap<>();

    // 十个"抽象"技能（伤害全部压低，数值风格与现有技能一致）
    public int cowRainCount = 3;                 // 奶牛雨的牛数量
    public double cowRainDamage = 2.0;
    public double milkBombDamage = 0.5;
    public int milkBombSlownessTicks = 100;
    public double mooWaveDamage = 1.0;
    public double mooWaveKnockback = 0.35;
    public double hayTornadoDamage = 0.4;
    public double butterflyDamage = 0.5;
    public int butterflyCount = 3;
    public double zeroGravityLift = 0.22;
    public double teleportSwapDamage = 1.5;
    public double confettiDamage = 0.5;
    public int quirkSkillDurationTicks = 80;     // 这批抽象技能的持续时间（4 秒）

    /** Schema version so one-shot default migrations never overwrite user-chosen values on reload. */
    public int configVersion = 0;
    public static final int CURRENT_CONFIG_VERSION = 2;

    public static SuperCowConfig load(File file) {
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                SuperCowConfig loaded = GSON.fromJson(reader, SuperCowConfig.class);
                if (loaded != null) {
                    boolean migrated = loaded.repairFollowDefaults();
                    if (migrated) {
                        loaded.save(file);
                    }
                    return loaded;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        SuperCowConfig config = new SuperCowConfig();
        config.configVersion = CURRENT_CONFIG_VERSION;
        config.save(file);
        return config;
    }

    /**
     * 老版本写出来的配置文件里 followDistance 是 4 或 10（当时觉得"够远了"，现在觉得吵），
     * buffer 也还是旧的 1.5。通过 configVersion 做一次性升级迁移并写回，之后用户再用
     * /supercow follow 4 或 10 设置的值在 reload/restart 时不会被覆盖。
     */
    public boolean repairFollowDefaults() {
        boolean migrated = false;
        if (configVersion < CURRENT_CONFIG_VERSION) {
            if (Math.abs(followDistance - 4.0D) < 1.0E-6D || Math.abs(followDistance - 10.0D) < 1.0E-6D) {
                followDistance = 20.0D;
            }
            if (Math.abs(followStartBuffer - 1.5D) < 1.0E-6D) {
                followStartBuffer = 4.0D;
            }
            if (Math.abs(teleportDistance - 24.0D) < 1.0E-6D) {
                teleportDistance = 32.0D;
            }
            if (Math.abs(teleportToOwnerDistance - 24.0D) < 1.0E-6D) {
                teleportToOwnerDistance = 32.0D;
            }
            configVersion = CURRENT_CONFIG_VERSION;
            migrated = true;
        }
        if (followDistance < 2.0D) {
            followDistance = 2.0D;
        } else if (followDistance > 24.0D) {
            followDistance = 24.0D;
        }
        if (followChaseBand < 1.2D) {
            followChaseBand = 1.25D;
        }
        if (followBrakeDistance <= 0.0D) {
            followBrakeDistance = 3.0D;
        }
        if (followMoveSpeed <= 0.0D) {
            followMoveSpeed = 1.2D;
        }
        double minTeleport = followDistance + Math.max(0.0D, followStartBuffer) + 8.0D;
        if (teleportDistance < minTeleport) {
            teleportDistance = Math.max(32.0D, minTeleport);
        }
        if (teleportToOwnerDistance < minTeleport) {
            teleportToOwnerDistance = Math.max(32.0D, minTeleport);
        }
        return migrated;
    }

    /** 技能是否启用（缺省全部启用；sky_spiral 走自己的开关） */
    public boolean isSkillEnabled(String skillId) {
        if (skillId == null) {
            return false;
        }
        if ("sky_spiral".equals(skillId)) {
            return skySpiralEnabled;
        }
        Boolean v = skillEnabled != null ? skillEnabled.get(skillId) : null;
        return v == null || v;
    }

    public void setSkillEnabled(String skillId, boolean enabled) {
        if (skillId == null) {
            return;
        }
        if ("sky_spiral".equals(skillId)) {
            skySpiralEnabled = enabled;
            return;
        }
        if (skillEnabled == null) {
            skillEnabled = new java.util.LinkedHashMap<>();
        }
        skillEnabled.put(skillId, enabled);
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
