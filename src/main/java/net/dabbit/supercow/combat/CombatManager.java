package net.dabbit.supercow.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.config.SuperCowConfig;
import net.dabbit.supercow.data.PetData;
import net.dabbit.supercow.pathfinding.PathfindingManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.phys.AABB;

/**
 * Orchestrates SuperCow combat behavior, modes, rage mode, and special attacks.
 *
 * Bug fixes carried over from the Bukkit 1.19.4 original:
 * 1. CombatManager.kt:647 "exploding_cow" branch mistakenly called shootPotion instead of
 *    launchExplodingCow - fixed in executeNamedAttack().
 * 2. SuperCow.onPetDeath removed the owner from activePets BEFORE calling combatManager.onPetDeath,
 *    so the handler returned early and never cleaned targets/glowing/tasks/summons - fixed by
 *    passing ownerUuid + pet explicitly to onPetRemovedOrDied().
 * 3. recallPet never called combat cleanup, and onDisable never cleaned up combat state.
 */
public class CombatManager {
    public enum PetMode {
        PROTECTING("护主模式"),
        AGGRESSIVE("主动攻击"),
        DEFENSIVE("防御模式"),
        PASSIVE("被动跟随");

        private final String displayName;

        PetMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static PetMode fromString(String name) {
            if (name == null) {
                return PROTECTING;
            }
            for (PetMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return PROTECTING;
        }
    }

    private final SuperCowMod mod;
    private final PathfindingManager pathfindingManager;
    private final SummonManager summonManager;
    private final Random random = new Random();

    // Reusable scratch buffers for rollWeightedSkill (attack hot path: no per-attack allocation).
    private java.util.List<Skills.Skill> rollScratchState;
    private Skills.Skill[] rollScratchAll = new Skills.Skill[0];
    private Skills.Skill[] rollPoolNormal = new Skills.Skill[160];
    private Skills.Skill[] rollPoolRage = new Skills.Skill[160];

    private final Map<UUID, PetMode> petModes = new HashMap<>();
    private final Map<UUID, LivingEntity> currentTargets = new HashMap<>();
    private final Map<UUID, Long> nextAttackTick = new HashMap<>();
    private final Map<UUID, EvasionStep> evasionSteps = new HashMap<>();
    private static final class EvasionStep {
        final UUID target;
        final long until;
        EvasionStep(UUID target, long until) { this.target = target; this.until = until; }
    }
    private final Map<UUID, Long> lastSpecialAttackTick = new HashMap<>();
    // Guard against targets that stopped ticking (e.g. entities frozen by another mod):
    // such entities never decrement invulnerableTime, so every hurt() call silently fails
    // and the pet would hammer them forever.
    private final Map<UUID, Integer> targetTickSnapshots = new HashMap<>();
    private final Map<UUID, Long> targetStuckSinceTick = new HashMap<>();
    // Entities proven untickable are skipped for 600 ticks (30 s) instead of being
    // re-acquired every 3 seconds while the pet has nothing else to do.
    private final Map<UUID, Long> untickableTargets = new HashMap<>();
    private final Map<UUID, Integer> untickableSnapshots = new HashMap<>();
    private final Map<UUID, Long> rageEndTick = new HashMap<>();
    private final Map<UUID, String> currentSkillIds = new HashMap<>();
    private final Map<UUID, Long> currentSkillEndTick = new HashMap<>();
    private static final int SKILL_DISPLAY_TICKS = 40;

    /** Server-thread API; UUIDs identify owners, not pet entities. Null means no recent cast. */
    public String getCurrentSkillId(UUID ownerUuid) {
        return getCurrentSkillRemainingTicks(ownerUuid) > 0 ? currentSkillIds.get(ownerUuid) : null;
    }

    /** Display TTL only, not the lifetime of projectiles, summons, or ongoing effects. */
    public int getCurrentSkillRemainingTicks(UUID ownerUuid) {
        Long end = currentSkillEndTick.get(ownerUuid);
        long remaining = end == null ? 0L : end - mod.getServerTicks();
        if (remaining <= 0L) {
            currentSkillIds.remove(ownerUuid);
            currentSkillEndTick.remove(ownerUuid);
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    public int getAttackCooldownRemainingTicks(UUID ownerUuid) {
        Long next = nextAttackTick.get(ownerUuid);
        if (next == null) return 0;
        long remaining = next - mod.getServerTicks();
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, remaining));
    }

    /** Notify UI of an actually triggered external skill (e.g. automatic sky spiral), server thread only. */
    public void notifySkillTriggered(UUID ownerUuid, String skillId, int displayTicks) {
        if (ownerUuid == null || skillId == null || displayTicks <= 0) return;
        currentSkillIds.put(ownerUuid, canonicalSkillId(skillId));
        currentSkillEndTick.put(ownerUuid, mod.getServerTicks() + displayTicks);
    }

    private static int attackCooldownTicks(boolean rage, SuperCowConfig cfg) {
        // Vanilla rejects equal/lower damage while invulnerableTime > 10. Never reset that timer.
        return Math.max(11, rage ? cfg.rageAttackCooldownTicks : cfg.attackCooldownTicks);
    }

    public static boolean isBossTarget(LivingEntity target, SuperCowConfig cfg) {
        return CombatBalance.isBoss(target, cfg);
    }

    private int sampleAttackIntervalTicks(boolean rage, LivingEntity target, SuperCowConfig cfg) {
        int jitter = Math.max(0, Math.min(20, cfg.attackIntervalJitterTicks));
        double base = attackCooldownTicks(rage, cfg);
        if (isBossTarget(target, cfg)) base *= Math.max(1.0D, cfg.bossAttackIntervalMultiplier);
        int sampled = (int) Math.ceil(base) + (jitter == 0 ? 0 : random.nextInt(jitter * 2 + 1) - jitter);
        // Combat dispatch runs every two ticks: store the real even interval for truthful GUI.
        return ((Math.max(12, sampled) + 1) / 2) * 2;
    }

    private static String canonicalSkillId(String skillId) {
        String id = skillId.toLowerCase(java.util.Locale.ROOT);
        switch (id) {
            case "explodingcow": return "exploding_cow";
            case "parrot": case "parrots": return "summon_parrots";
            case "rabbit": case "rabbits": return "summon_rabbits";
            case "spiral": case "sky": case "fly": return "sky_spiral";
            default: return id;
        }
    }

    public CombatManager(SuperCowMod mod, PathfindingManager pathfindingManager, SummonManager summonManager) {
        this.mod = mod;
        this.pathfindingManager = pathfindingManager;
        this.summonManager = summonManager;
    }

    public PetMode getPetMode(UUID ownerUuid) {
        return petModes.getOrDefault(ownerUuid, PetMode.PROTECTING);
    }

    public void setPetMode(UUID ownerUuid, PetMode mode) {
        petModes.put(ownerUuid, mode);
        PetData data = mod.getPetData(ownerUuid);
        if (data != null) {
            data.combatMode = mode.name();
        }
        if (mode == PetMode.PASSIVE) {
            clearTarget(ownerUuid);
            Cow pet = mod.getActivePet(ownerUuid);
            if (pet != null) {
                pathfindingManager.stopPathfinding(pet);
            }
        }
    }

    public LivingEntity getCurrentTarget(UUID ownerUuid) {
        LivingEntity target = currentTargets.get(ownerUuid);
        if (target != null && (!target.isAlive() || target.removed || target.isDeadOrDying() || target.getHealth() <= 0.0F)) {
            clearTarget(ownerUuid);
            return null;
        }
        if (target != null) {
            UUID targetId = target.getUUID();
            int tickCount = target.tickCount;
            Integer previous = targetTickSnapshots.get(targetId);
            if (previous == null || previous.intValue() != tickCount) {
                targetTickSnapshots.put(targetId, tickCount);
                targetStuckSinceTick.remove(targetId);
            } else {
                long since = targetStuckSinceTick.computeIfAbsent(targetId, k -> mod.getServerTicks());
                if (mod.getServerTicks() - since >= 60L) {
                    // The entity has not advanced a single tick for 3 seconds: it is not
                    // being ticked at all, so every attack would silently fail.
                    targetTickSnapshots.remove(targetId);
                    targetStuckSinceTick.remove(targetId);
                    untickableTargets.put(targetId, mod.getServerTicks() + 600L);
                    untickableSnapshots.put(targetId, target.tickCount);
                    if (target.isDeadOrDying() || target.getHealth() <= 0.0F) {
                        // Frozen corpse: vanilla will never remove it, clean it up.
                        target.remove();
                    }
                    clearTarget(ownerUuid);
                    return null;
                }
            }
        }
        return target;
    }

    public void setTarget(UUID ownerUuid, Cow pet, LivingEntity target) {
        if (!TargetValidator.isValidCombatTarget(target, ownerUuid, pet, true)) {
            return;
        }
        LivingEntity prev = currentTargets.put(ownerUuid, target);
        if (prev != null && prev != target && prev.isAlive()) {
            prev.setGlowing(false);
        }
        target.setGlowing(true);
    }

    public void clearTarget(UUID ownerUuid) {
        evasionSteps.remove(ownerUuid);
        pathfindingManager.stopCombatStep(mod.getActivePet(ownerUuid));
        LivingEntity prev = currentTargets.remove(ownerUuid);
        if (prev != null && prev.isAlive()) {
            prev.setGlowing(false);
        }
        // FIXED LEAK: prune per-target tick tracking maps on target release, while keeping
        // untickableTargets/untickableSnapshots until their 600-tick blacklist window expires
        // (and cleaning any already-expired blacklist entries opportunistically).
        if (prev != null) {
            UUID id = prev.getUUID();
            targetTickSnapshots.remove(id);
            targetStuckSinceTick.remove(id);
        }
        if (!untickableTargets.isEmpty()) {
            long now = mod.getServerTicks();
            Iterator<Map.Entry<UUID, Long>> it = untickableTargets.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> e = it.next();
                if (e.getValue() == null || e.getValue().longValue() <= now) {
                    untickableSnapshots.remove(e.getKey());
                    it.remove();
                }
            }
        }
    }

    public boolean isInRageMode(UUID ownerUuid) {
        Long end = rageEndTick.get(ownerUuid);
        if (end == null) {
            return false;
        }
        if (mod.getServerTicks() >= end) {
            rageEndTick.remove(ownerUuid);
            return false;
        }
        return true;
    }

    public void triggerRageMode(UUID ownerUuid, Cow pet, ServerPlayer owner) {
        SuperCowConfig cfg = mod.getConfig();
        long until = mod.getServerTicks() + cfg.rageDurationTicks;
        boolean wasRaging = isInRageMode(ownerUuid);
        rageEndTick.put(ownerUuid, until);

        if (pet != null && pet.isAlive()) {
            pet.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, cfg.rageDurationTicks, 0, false, true));
            pet.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, cfg.rageDurationTicks, 0, false, true));
            pet.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, cfg.rageDurationTicks, 0, false, true));
            if (pet.level instanceof ServerLevel) {
                ServerLevel level = (ServerLevel) pet.level;
                level.sendParticles(ParticleTypes.ANGRY_VILLAGER, pet.getX(), pet.getY() + 1.1D, pet.getZ(), 10, 0.5D, 0.5D, 0.5D, 0.05D);
                level.playSound(null, pet.getX(), pet.getY(), pet.getZ(), SoundEvents.COW_HURT, SoundSource.NEUTRAL, 0.9F, 0.7F);
            }
        }
        if (!wasRaging && owner != null) {
            owner.sendMessage(new TextComponent("\u00A7c\u00A7l[超级牛] 你的牛牛进入了狂暴护主状态（持续 " + (cfg.rageDurationTicks / 20) + " 秒）！"), ownerUuid);
        }
    }

    public void onOwnerOrPetAttacked(UUID ownerUuid, Cow pet, ServerPlayer owner, LivingEntity attacker) {
        if (pet == null || !pet.isAlive()) {
            return;
        }
        if (getPetMode(ownerUuid) == PetMode.PASSIVE) {
            return;
        }
        if (!TargetValidator.isValidCombatTarget(attacker, ownerUuid, pet, true)) {
            return;
        }
        setTarget(ownerUuid, pet, attacker);
        summonManager.notifyOwnerOrPetAttacked(ownerUuid, attacker);

        if ((owner != null && owner.getHealth() <= owner.getMaxHealth() * 0.5F)
            || pet.getHealth() <= pet.getMaxHealth() * 0.5F
            || random.nextDouble() < 0.25D) {
            triggerRageMode(ownerUuid, pet, owner);
        }
    }

    public void onOwnerAttackTarget(UUID ownerUuid, Cow pet, ServerPlayer owner, LivingEntity victim) {
        if (pet == null || !pet.isAlive()) {
            return;
        }
        PetMode mode = getPetMode(ownerUuid);
        if (mode == PetMode.PASSIVE || mode == PetMode.DEFENSIVE) {
            return;
        }
        if (!TargetValidator.isValidCombatTarget(victim, ownerUuid, pet, true)) {
            return;
        }
        setTarget(ownerUuid, pet, victim);
        summonManager.notifyOwnerOrPetAttacked(ownerUuid, victim);
    }

    public void tickPetCombat(UUID ownerUuid, Cow pet, ServerPlayer owner, PetData data) {
        if (pet == null || !pet.isAlive() || pet.removed || !(pet.level instanceof ServerLevel)) {
            return;
        }
        ServerLevel level = (ServerLevel) pet.level;
        SuperCowConfig cfg = mod.getConfig();
        long nowTick = mod.getServerTicks();
        PetMode mode = getPetMode(ownerUuid);
        boolean rage = isInRageMode(ownerUuid);

        if (rage && nowTick % 10 == 0) {
            level.sendParticles(ParticleTypes.FLAME, pet.getX(), pet.getY() + 0.8D, pet.getZ(), 3, 0.3D, 0.3D, 0.3D, 0.01D);
        }

        if (mode == PetMode.PASSIVE) {
            followOwnerIfNeeded(pet, owner, cfg);
            return;
        }

        LivingEntity target = getCurrentTarget(ownerUuid);
        if (target != null) {
            if (!TargetValidator.isValidCombatTarget(target, ownerUuid, pet, true)
                || pet.distanceTo(target) > cfg.maxTargetDistance
                || target.level != pet.level) {
                clearTarget(ownerUuid);
                target = null;
            }
        }

        if (target == null && (mode == PetMode.AGGRESSIVE || mode == PetMode.PROTECTING)) {
            double searchRadius = mode == PetMode.AGGRESSIVE ? cfg.detectionRange : cfg.protectRange;
            AABB box = pet.getBoundingBox().inflate(searchRadius);
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (!TargetValidator.isValidCombatTarget(candidate, ownerUuid, pet, false)) {
                    continue;
                }
                if (isUnclickableCandidate(candidate, nowTick)) {
                    continue;
                }
                double d = pet.distanceToSqr(candidate);
                if (d < bestDist) {
                    bestDist = d;
                    target = candidate;
                }
            }
            if (target != null) {
                setTarget(ownerUuid, pet, target);
            }
        }

        if (target == null) {
            followOwnerIfNeeded(pet, owner, cfg);
            return;
        }

        PathfindingManager.faceEntity(pet, target);
        double dist = pet.distanceTo(target);
        double moveSpeed = rage ? 1.35D : 1.15D;

        updateCombatMovement(ownerUuid, pet, target, dist, moveSpeed, nowTick, cfg);

        if (nowTick < nextAttackTick.getOrDefault(ownerUuid, nowTick)) {
            return;
        }
        // A recent hit from the owner/another mob must not consume a whole melee cooldown.
        if (dist <= cfg.closeRangeDistance + 0.5D && target.invulnerableTime > 10) {
            return;
        }
        nextAttackTick.put(ownerUuid, nowTick + sampleAttackIntervalTicks(rage, target, cfg));

        performCombatAction(level, ownerUuid, pet, owner, target, data, dist, rage, nowTick, cfg);
        if (target.isAlive() && dist <= 6.0D) startEvasion(ownerUuid, pet, target, nowTick, cfg);
    }

    private void updateCombatMovement(UUID ownerUuid, Cow pet, LivingEntity target, double dist,
                                      double speed, long now, SuperCowConfig cfg) {
        EvasionStep step = evasionSteps.get(ownerUuid);
        if (cfg.combatEvasionEnabled && step != null && step.target.equals(target.getUUID())
            && now < step.until && dist < 8.0D && !pathfindingManager.isSpiraling(pet.getUUID())) {
            PathfindingManager.faceEntity(pet, target);
            return; // Keep the validated waypoint, rather than resetting it every combat tick.
        }
        evasionSteps.remove(ownerUuid);
        if (cfg.combatEvasionEnabled && nextAttackTick.getOrDefault(ownerUuid, now) - now > 6L
            && dist < 4.5D && startEvasion(ownerUuid, pet, target, now, cfg)) return;
        if (dist > cfg.closeRangeDistance) pathfindingManager.moveToEntity(pet, target, speed);
        else pathfindingManager.stopPathfinding(pet);
        PathfindingManager.faceEntity(pet, target);
    }

    private boolean startEvasion(UUID ownerUuid, Cow pet, LivingEntity target, long now, SuperCowConfig cfg) {
        if (!cfg.combatEvasionEnabled || pathfindingManager.isSpiraling(pet.getUUID())) return false;
        net.minecraft.world.phys.Vec3 away = pet.position().subtract(target.position()).multiply(1.0D, 0.0D, 1.0D);
        if (away.lengthSqr() < 0.01D) away = new net.minecraft.world.phys.Vec3(1.0D, 0.0D, 0.0D);
        away = away.normalize();
        double length = Double.isFinite(cfg.combatRetreatDistance) ? Math.max(0.5D, Math.min(4.0D, cfg.combatRetreatDistance)) : 2.5D;
        int side = ((ownerUuid.hashCode() & 1) == 0) ? 1 : -1;
        // Back-diagonal first, opposite side second, pure lateral last. No teleport or knockback.
        double[] angles = {side * 35.0D, -side * 35.0D, side * 80.0D, -side * 80.0D};
        for (double angle : angles) {
            double rad = Math.toRadians(angle), c = Math.cos(rad), s = Math.sin(rad);
            net.minecraft.world.phys.Vec3 offset = new net.minecraft.world.phys.Vec3(away.x * c - away.z * s, 0.0D, away.x * s + away.z * c).scale(length);
            if (pathfindingManager.moveToCombatPosition(pet, pet.position().add(offset), 1.25D)) {
                evasionSteps.put(ownerUuid, new EvasionStep(target.getUUID(), now + Math.max(2, Math.min(16, cfg.combatRetreatTicks))));
                PathfindingManager.faceEntity(pet, target);
                return true;
            }
        }
        return false; // Confined/unsafe: preserve ordinary combat rather than force an unsafe dodge.
    }

    /** True while a previously frozen entity is still frozen and still on the blacklist. */
    private boolean isUnclickableCandidate(LivingEntity candidate, long nowTick) {
        UUID id = candidate.getUUID();
        Long until = untickableTargets.get(id);
        if (until == null) {
            return false;
        }
        if (until.longValue() <= nowTick) {
            untickableTargets.remove(id);
            untickableSnapshots.remove(id);
            return false;
        }
        Integer snapshot = untickableSnapshots.get(id);
        if (snapshot != null && snapshot.intValue() != candidate.tickCount) {
            // It ticks again: the freeze is over, allow it as a target.
            untickableTargets.remove(id);
            untickableSnapshots.remove(id);
            return false;
        }
        return true;
    }

    /**
     * Keep the passive pet near its owner.
     *
     * <p>Two thresholds, one owner lookup, one distance computation per call:</p>
     * <ul>
     *   <li><b>start</b> = followDistance + followStartBuffer (default 20 + 4 = 24): the pet is only
     *       asked to move once it has clearly drifted out of the stand-off radius, which is what
     *       stops the "shuffle back and forth" jitter the player complained about.</li>
     *   <li><b>stop</b> = followDistance (default 20): once the path is a follow path, the pet
     *       keeps walking until it has actually reached the stand-off radius. The deceleration
     *       curve itself lives in PathfindingManager (followBands), so the pet is already crawling
     *       for the last few blocks and stops at followDistance instead of running into the
     *       player. When the owner walks towards the pet it can still close up to
     *       followDistance - followStopDistance (default 18.5) before the path is dropped.</li>
     * </ul>
     * <p>The old code stopped the path as soon as the pet was within 1.5 blocks, which is why the
     * pet ended up glued to the player even with a "far" follow distance.</p>
     */
    private void followOwnerIfNeeded(Cow pet, ServerPlayer owner, SuperCowConfig cfg) {
        if (owner == null || !owner.isAlive() || owner.level != pet.level) {
            return;
        }
        // Horizontal distance for walking/braking so a simple jump does not re-trigger walking,
        // plus vertical separation so climbing a tower or vertical /tp still teleports/follows.
        double dx = pet.getX() - owner.getX();
        double dy = Math.abs(pet.getY() - owner.getY());
        double dz = pet.getZ() - owner.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double dist3d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double follow = Math.max(2.0D, cfg.followDistance);
        double startRadius = follow + Math.max(0.0D, cfg.followStartBuffer);
        double teleport = Math.max(startRadius + 4.0D, cfg.teleportDistance);
        if (dist3d > teleport || dy > 16.0D) {
            pathfindingManager.stopPathfinding(pet);
            double tx = owner.getX() + 1.5D;
            double tz = owner.getZ() + 1.5D;
            double ty = owner.getY();
            if (owner.level instanceof ServerLevel) {
                double groundY = SuperCowMod.groundBelow((ServerLevel) owner.level, tx, ty + 2.0D, tz);
                if (!Double.isNaN(groundY)) {
                    ty = groundY;
                }
            }
            pet.teleportTo(tx, ty, tz);
            pet.setDeltaMovement(0.0D, 0.0D, 0.0D);
            return;
        }
        double stopRadius = pathfindingManager.followStopRadius(pet.getUUID());
        if (stopRadius > 0.0D) {
            // A follow path is running: the mover brakes over followBrakeDistance and parks at
            // followDistance, so it is dropped exactly there instead of after gliding inside it.
            if (dist <= stopRadius && dy <= 6.0D) {
                pathfindingManager.stopPathfinding(pet);
            }
        } else if (dist > startRadius || dy > 8.0D) {
            // Full speed in the dispatcher: the actual walking speed comes from the follow bands.
            pathfindingManager.moveToEntity(pet, owner, 1.0D, true);
        }
    }

    /**
     * Safety net for the passive follow (see {@link #followOwnerIfNeeded}): a no-AI pet only walks
     * while it owns a follow path, and that path is dropped every time the pet parks inside its
     * stand-off radius. If the regular re-arm (this class's pet tick) is ever skipped - target
     * invalidated, owner temporarily unresolvable, entity flagged removed - the pet would stand
     * still forever while the player walks away. This scans the online players once per tick and
     * rebuilds the missing path. It never touches a pet that already has a route, so it cannot
     * fight the combat dispatcher.
     */
    public void maintainPassiveFollow() {
        SuperCowMod mod = SuperCowMod.getInstance();
        if (mod == null) {
            return;
        }
        SuperCowConfig cfg = mod.getConfig();
        MinecraftServer server = SuperCowMod.getServer();
        if (cfg == null || server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || !player.isAlive()) {
                continue;
            }
            UUID ownerUuid = player.getUUID();
            PetData data = mod.getPetData(ownerUuid);
            if (data == null || !data.isAlive) {
                continue;
            }
            Cow pet = mod.getActivePet(ownerUuid);
            // A pet that merely stopped moving (previous tick flagged it removed while it was being
            // re-added, or the teleport glitch below) is still the pet: revive the link instead of
            // dropping the owner. Only a genuinely dead entity is skipped.
            if (pet == null) {
                pet = adoptOrphanPet(mod, player);
            }
            if (pet == null || !pet.isAlive()) {
                continue;
            }
            if (pet.level != player.level) {
                continue;
            }
            PetMode mode = getPetMode(ownerUuid);
            LivingEntity activeTarget = getCurrentTarget(ownerUuid);
            if (mode != PetMode.PASSIVE && activeTarget != null && activeTarget.isAlive() && !activeTarget.removed) {
                continue;
            }
            // 卡住兜底：牛在长距离跟随/瞬移里偶尔会把自己写进方块里、或者掉到世界底下，
            // 之后 mover 每一 tick 都撞着方块原地踏步，看起来就是"牛永远不追了"。
            // 一直动不了、而且离玩家还很远时，直接按安全落点把它挪回玩家身边。
            if (rescueStuckPet(player, pet, cfg)) {
                continue;
            }
            UUID petId = pet.getUUID();
            if (pathfindingManager.hasActivePath(petId) || pathfindingManager.isSpiraling(petId)) {
                continue;
            }
            double dx = pet.getX() - player.getX();
            double dy = Math.abs(pet.getY() - player.getY());
            double dz = pet.getZ() - player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double follow = Math.max(2.0D, cfg.followDistance);
            double startRadius = follow + Math.max(0.0D, cfg.followStartBuffer);
            if (dist > startRadius || dy > 8.0D) {
                pathfindingManager.moveToEntity(pet, player, 1.0D, true);
            }
        }
    }

    /** Ticks a pet may spend "wanting to move" without covering ground before it is rescued. */
    private static final int STUCK_RESCUE_TICKS = 100;
    private static final int STUCK_NO_PROGRESS_TICKS = 140;
    private static final double STUCK_MIN_TRAVEL = 1.6D;
    private static final int PET_LINK_MEMORY_TICKS = 400;
    private final Map<UUID, Integer> followStuckTicks = new HashMap<>();
    private final Map<UUID, net.minecraft.world.phys.Vec3> followStuckAnchor = new HashMap<>();
    private final Map<UUID, Integer> followNoProgressTicks = new HashMap<>();
    private final Map<UUID, Double> followBestDist = new HashMap<>();
    /** Pet entities still tagged as this player's, even if the fast map lost them. */
    private final Map<UUID, UUID> petLinkByOwner = new HashMap<>();
    private final Map<UUID, Long> petLinkSeenAt = new HashMap<>();
    /** Cooldown timestamp (ms) before re-scanning nearby chunks after an orphan scan missed. */
    private final Map<UUID, Long> orphanScanCooldownUntil = new HashMap<>();

    /**
     * Re-link a player with the pet cow of theirs that is still in the world (tagged
     * {@code supercow_pet} + {@code supercow_owner:&lt;uuid&gt;}). Used when the cached link in
     * SuperCowMod went missing but the entity itself is alive - the pet must not be "gone" just
     * because a tick raced with a re-add.
     */
    private Cow adoptOrphanPet(SuperCowMod mod, ServerPlayer player) {
        UUID ownerUuid = player.getUUID();
        PetData data = mod.getPetData(ownerUuid);
        if (data == null || !data.isAlive) {
            return null;
        }
        long now = System.currentTimeMillis();
        Long cooldownUntil = orphanScanCooldownUntil.get(ownerUuid);
        if (cooldownUntil != null && now < cooldownUntil.longValue()) {
            return null;
        }
        Long seen = petLinkSeenAt.get(ownerUuid);
        if (seen != null && now - seen > 500L) {
            petLinkByOwner.remove(ownerUuid);
            petLinkSeenAt.remove(ownerUuid);
        }
        if (!(player.level instanceof ServerLevel)) {
            return null;
        }
        String ownerTag = "supercow_owner:" + ownerUuid;
        AABB box = player.getBoundingBox().inflate(96.0D);
        Cow best = null;
        double bestDist = Double.MAX_VALUE;
        for (Cow cow : ((ServerLevel) player.level).getEntitiesOfClass(Cow.class, box)) {
            if (cow == null || !cow.isAlive() || cow.removed) {
                continue;
            }
            if (!cow.getTags().contains(SuperCowMod.TAG_PET) || !cow.getTags().contains(ownerTag)) {
                continue;
            }
            double d = cow.distanceToSqr(player);
            if (d < bestDist) {
                bestDist = d;
                best = cow;
            }
        }
        if (best != null) {
            petLinkByOwner.put(ownerUuid, best.getUUID());
            petLinkSeenAt.put(ownerUuid, now);
            orphanScanCooldownUntil.remove(ownerUuid);
            mod.bindPetEntity(ownerUuid, best);
        } else {
            orphanScanCooldownUntil.put(ownerUuid, now + 1000L);
        }
        return best;
    }

    /**
     * Teleport the pet back next to its owner when it has been trying to follow for several seconds
     * without actually covering any ground (wedged in terrain, void, unloaded chunk). Returns true
     * when it rescued the pet on this tick.
     */
    private boolean rescueStuckPet(ServerPlayer player, Cow pet, SuperCowConfig cfg) {
        UUID id = pet.getUUID();
        double dx = pet.getX() - player.getX();
        double dy = Math.abs(pet.getY() - player.getY());
        double dz = pet.getZ() - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double follow = Math.max(2.0D, cfg.followDistance);
        double startRadius = follow + Math.max(0.0D, cfg.followStartBuffer);
        boolean activeFollow = pathfindingManager.followStopRadius(id) > 0.0D && dist > follow + 0.5D;
        boolean wantsToMove = dist > startRadius || dy > 8.0D || activeFollow;
        if (!wantsToMove) {
            followStuckTicks.remove(id);
            followStuckAnchor.remove(id);
            followNoProgressTicks.remove(id);
            followBestDist.remove(id);
            return false;
        }
        net.minecraft.world.phys.Vec3 anchor = followStuckAnchor.get(id);
        double movedHoriz = 0.0D;
        if (anchor != null) {
            double ax = pet.getX() - anchor.x;
            double az = pet.getZ() - anchor.z;
            movedHoriz = Math.sqrt(ax * ax + az * az);
        }
        int stuckTicks;
        if (anchor == null || movedHoriz > STUCK_MIN_TRAVEL) {
            followStuckAnchor.put(id, pet.position());
            stuckTicks = 0;
            followStuckTicks.put(id, 0);
        } else {
            stuckTicks = followStuckTicks.getOrDefault(id, 0) + 1;
            followStuckTicks.put(id, stuckTicks);
        }

        Double bestDist = followBestDist.get(id);
        int noProgressTicks;
        if (bestDist == null || dist < bestDist.doubleValue() - 0.8D || dist > bestDist.doubleValue() + 3.0D) {
            followBestDist.put(id, dist);
            noProgressTicks = 0;
            followNoProgressTicks.put(id, 0);
        } else {
            noProgressTicks = followNoProgressTicks.getOrDefault(id, 0) + 1;
            followNoProgressTicks.put(id, noProgressTicks);
        }

        if (stuckTicks < STUCK_RESCUE_TICKS && noProgressTicks < STUCK_NO_PROGRESS_TICKS) {
            return false;
        }
        followStuckTicks.remove(id);
        followStuckAnchor.remove(id);
        followNoProgressTicks.remove(id);
        followBestDist.remove(id);
        pathfindingManager.stopPathfinding(pet);
        double tx = player.getX() + 1.5D;
        double ty = player.getY();
        double tz = player.getZ() + 1.5D;
        if (player.level instanceof ServerLevel) {
            double groundY = SuperCowMod.groundBelow((ServerLevel) player.level, tx, ty + 2.0D, tz);
            if (!Double.isNaN(groundY)) {
                ty = groundY;
            }
        }
        pet.teleportTo(tx, ty, tz);
        pet.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.sendMessage(new TextComponent("\u00A7e[\u8D85\u7EA7\u725B] \u4F60\u7684\u725B\u5361\u4F4F\u4E86\uFF0C\u5DF2\u628A\u5B83\u62C9\u56DE\u8EAB\u8FB9\u3002"), player.getUUID());
        return true;
    }

    private void performCombatAction(
        ServerLevel level,
        UUID ownerUuid,
        Cow pet,
        ServerPlayer owner,
        LivingEntity target,
        PetData data,
        double dist,
        boolean rage,
        long nowTick,
        SuperCowConfig cfg
    ) {
        long lastSpecial = lastSpecialAttackTick.getOrDefault(ownerUuid, -9999L);
        boolean boss = isBossTarget(target, cfg);
        int specialCooldown = boss ? cfg.bossSpecialAttackCooldownTicks : cfg.specialAttackCooldownTicks;
        boolean canSpecial = (nowTick - lastSpecial) >= Math.max(40, specialCooldown);
        double specialProb = rage ? cfg.rageSpecialAttackChance : cfg.specialAttackChance;
        if (boss) specialProb = Math.max(specialProb, cfg.bossSpecialAttackChance);
        specialProb = Math.max(0.0D, Math.min(1.0D, specialProb));

        if (canSpecial && random.nextDouble() < specialProb) {
            int capacity = cfg.multiSkillEnabled ? (int) Math.min(Math.max(1, Math.min(4, cfg.multiSkillMaxCount)),
                1L + Math.max(0, data.level) / Math.max(1, cfg.multiSkillLevelStep)) : 1;
            java.util.Set<String> drawn = new java.util.HashSet<>();
            drawn.add("arrow"); // Basic ranged fallback is not an extra special-skill slot.
            int released = 0;
            int budget = Math.max(16, Math.min(512, cfg.combatSkillEntityBudget));
            for (int tries = 0; tries < Skills.all().size() && released < capacity; tries++) {
                if (SkillCleanup.countAll() >= budget || !target.isAlive()) break;
                String chosen = cfg.legacySkillsEnabled ? rollWeightedSkill(rage, drawn) : chooseSpecialAttackByDistance(dist, cfg);
                if (chosen == null) break;
                if (!drawn.add(chosen)) { if (!cfg.legacySkillsEnabled && tries >= 16) break; else continue; }
                if (!cfg.isSkillEnabled(chosen)) continue;
                if (executeNamedAttack(level, ownerUuid, pet, owner, target, chosen, rage, false)) released++;
            }
            if (released > 0) {
                lastSpecialAttackTick.put(ownerUuid, nowTick);
                return;
            }
            // Failed summons/disabled casts should not leave an empty attack cycle.
        }

        if (dist <= cfg.closeRangeDistance + 0.5D) {
            performMeleeAttack(level, ownerUuid, pet, target, data, rage, cfg);
        } else {
            executeNamedAttack(level, ownerUuid, pet, owner, target, "arrow", rage, false);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Original skill table (CombatManager.kt:549-657). The Bukkit plugin did NOT pick a skill by
    // range: it used a weighted table, so a close-range pet could still
    // launch an exploding cow and a long-range pet could start a music attack. That unpredictability
    // is retained; special rounds draw distinct skills, with distance pools as the nonlegacy fallback.
    // -------------------------------------------------------------------------------------------
    // The table itself lives in Skills (weights normal/rage). Only "rollable" skills take part:
    // sky_spiral is exclusive to its own trigger, and anything the owner disabled in the GUI is
    // skipped, so a switched-off skill can never be drawn.
    private String rollWeightedSkill(boolean rage, java.util.Set<String> excluded) {
        SuperCowConfig cfg = mod.getConfig();
        // Rebuilt in place instead of allocating a fresh ArrayList per attack. Skills.all() is an
        // unmodifiable view whose contents can change when the owner toggles a skill, so the
        // candidate list is refreshed whenever the table identity changes, and the enabled flags
        // are re-checked every roll (that is a cheap array read, unlike the list copy).
        java.util.List<Skills.Skill> all = Skills.all();
        if (rollScratchState != all || rollScratchAll.length != all.size()) {
            rollScratchAll = new Skills.Skill[all.size()];
            for (int i = 0; i < rollScratchAll.length; i++) {
                rollScratchAll[i] = all.get(i);
            }
            rollScratchState = all;
        }
        Skills.Skill[] pool = rage ? rollPoolRage : rollPoolNormal;
        int size = 0;
        int total = 0;
        for (int i = 0; i < rollScratchAll.length; i++) {
            Skills.Skill skill = rollScratchAll[i];
            if (skill == null || !skill.rollable || excluded.contains(skill.id) || !cfg.isSkillEnabled(skill.id)) {
                continue;
            }
            int w = rage ? skill.weightRage : skill.weightNormal;
            if (w <= 0) {
                continue;
            }
            pool[size++] = skill;
            total += w;
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (int i = 0; i < size; i++) {
            Skills.Skill skill = pool[i];
            int w = rage ? skill.weightRage : skill.weightNormal;
            if (roll < w) {
                return skill.id;
            }
            roll -= w;
        }
        return "arrow";
    }

    private static final String[] POOL_CLOSE = {"music", "potion", "summon", "arrow"};
    private static final String[] POOL_MEDIUM = {"arrow", "fireball", "potion", "music", "summon"};
    private static final String[] POOL_FAR = {"arrow", "firework", "chicken", "exploding_cow"};

    private String chooseSpecialAttackByDistance(double dist, SuperCowConfig cfg) {
        String[] pool;
        if (dist <= cfg.closeRangeDistance) {
            pool = POOL_CLOSE;
        } else if (dist <= cfg.mediumRangeDistance) {
            pool = POOL_MEDIUM;
        } else {
            pool = POOL_FAR;
        }
        return pool[random.nextInt(pool.length)];
    }

    /** Single public combat-skill dispatch: only successful releases become visible to UI. */
    public boolean executeNamedAttack(
        ServerLevel level, UUID ownerUuid, Cow pet, ServerPlayer owner, LivingEntity target,
        String skillName, boolean rage, boolean manualCommand
    ) {
        if (skillName == null) return false;
        String id = canonicalSkillId(skillName);
        boolean released = dispatchNamedAttack(level, ownerUuid, pet, owner, target, id, rage, manualCommand);
        if (released) notifySkillTriggered(ownerUuid, id, SKILL_DISPLAY_TICKS);
        return released;
    }

    private boolean dispatchNamedAttack(
        ServerLevel level,
        UUID ownerUuid,
        Cow pet,
        ServerPlayer owner,
        LivingEntity target,
        String skillName,
        boolean rage,
        boolean manualCommand
    ) {
        if (skillName == null || pet == null || !pet.isAlive()) {
            return false;
        }
        switch (skillName.toLowerCase()) {
            case "arrow":
                SpecialAttackManagers.shootArrows(level, pet, ownerUuid, target, rage);
                return true;
            case "fireball":
                SpecialAttackManagers.shootFireball(level, pet, ownerUuid, target, rage);
                return true;
            case "potion":
                SpecialAttackManagers.shootPotion(level, pet, ownerUuid, target, rage);
                return true;
            case "firework":
                SpecialAttackManagers.launchFireworks(level, pet, ownerUuid, target, rage);
                return true;
            case "chicken":
                SpecialAttackManagers.startChickenBombardment(level, pet, ownerUuid, target, rage);
                return true;
            case "music":
                SpecialAttackManagers.startMusicAttack(level, pet, ownerUuid, target, rage);
                return true;
            case "exploding_cow":
            case "explodingcow": {
                SuperCowConfig cfg = mod.getConfig();
                // Quirk kept as a skill: the original 1.19.4 dispatch for "exploding_cow" actually
                // called shootPotion(), so one in five exploding cows is just a potion throw.
                if (cfg.legacyQuirksEnabled && cfg.explodingCowPotionBugChance > 0.0D
                    && Quirks.roll() && random.nextDouble() < cfg.explodingCowPotionBugChance) {
                    SpecialAttackManagers.shootPotion(level, pet, ownerUuid, target, rage);
                } else {
                    SpecialAttackManagers.launchExplodingCow(level, pet, ownerUuid, target, rage);
                }
                return true;
            }
            case "summon_parrots":
            case "parrot":
            case "parrots":
                MinionManager.spawnParrots(level, owner, pet, target, rage);
                return true;
            case "summon_rabbits":
            case "rabbit":
            case "rabbits":
                MinionManager.spawnRabbits(level, owner, pet, target, rage);
                return true;
            case "summon":
                // Quirk kept as a skill: the original consumeHayBale() started with an unconditional
                // "return true", so skill-triggered summons never paid for the hay bale. Only the
                // manual /supercow attack summon charges the player.
                // Manual casts always pay the hay bale; skill-triggered summons only skip the cost when
                // this cast rolled the original quirk (consumeHayBale() returned true unconditionally).
                boolean freeSummon = !manualCommand && Quirks.roll();
                return owner != null && summonManager.summonHelperCows(level, owner, pet, target, rage, freeSummon);
            case "rage":
                if (owner != null) {
                    triggerRageMode(ownerUuid, pet, owner);
                    return true;
                }
                return false;
            default: {
                String quirkId = skillName.toLowerCase();
                if (QuirkSkills.isQuirkSkill(quirkId)) {
                    return QuirkSkills.cast(level, pet, ownerUuid, target, rage, owner, quirkId);
                }
                return false;
            }
        }
    }

    private void performMeleeAttack(
        ServerLevel level,
        UUID ownerUuid,
        Cow pet,
        LivingEntity target,
        PetData data,
        boolean rage,
        SuperCowConfig cfg
    ) {
        int petLevel = data != null ? data.level : 1;
        double dmg = cfg.baseDamage + (petLevel - 1) * cfg.damagePerLevel;
        if (rage) {
            dmg *= cfg.rageDamageMultiplier;
        }
        boolean isCrit = random.nextDouble() < cfg.critChance;
        if (isCrit) {
            dmg *= cfg.critMultiplier;
        }

        SuperCowMod.recordDamageOwner(target.getUUID(), ownerUuid);
        boolean hurt = target.hurt(DamageSource.mobAttack(pet), (float) dmg);
        if (hurt) {
            double dx = target.getX() - pet.getX();
            double dz = target.getZ() - pet.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0E-4D) {
                double knockback = Math.max(0.0D, Math.min(1.0D, cfg.extraKnockbackMultiplier));
                target.push((dx / len) * 0.12D * knockback, 0.05D * knockback, (dz / len) * 0.12D * knockback);
            }
            if (isCrit) {
                level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(), 8, 0.25D, 0.25D, 0.25D, 0.1D);
                level.playSound(null, pet.getX(), pet.getY(), pet.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.NEUTRAL, 0.7F, 1.0F);
            } else {
                level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
                level.playSound(null, pet.getX(), pet.getY(), pet.getZ(), SoundEvents.COW_HURT, SoundSource.NEUTRAL, 0.6F, 1.1F);
            }
        }
    }

    /**
     * FIXED BUG: cleanup now takes the ownerUuid explicitly so it still runs after the pet
     * was already removed from the activePets map (the original returned early in that case).
     */
    public void onPetRemovedOrDied(UUID ownerUuid, Cow pet) {
        clearTarget(ownerUuid);
        nextAttackTick.remove(ownerUuid);
        lastSpecialAttackTick.remove(ownerUuid);
        currentSkillIds.remove(ownerUuid);
        currentSkillEndTick.remove(ownerUuid);
        rageEndTick.remove(ownerUuid);
        if (pet != null) {
            pathfindingManager.stopPathfinding(pet);
        }
        summonManager.cleanupSummonsForOwner(ownerUuid);
        MinionManager.cleanupForOwner(ownerUuid);
        SpecialAttackManagers.cleanupForOwner(ownerUuid);
        SkillCleanup.cleanupForOwner(ownerUuid);
        QuirkSkills.cleanupForOwner(ownerUuid);
    }

    /** Used by /supercow skill <name>: fire one specific skill at the current (or nearest) target. */
    public boolean forceSkill(ServerLevel level, UUID ownerUuid, String skillName) {
        Cow pet = mod.getActivePet(ownerUuid);
        ServerPlayer owner = mod.getPlayer(ownerUuid);
        if (pet == null || !pet.isAlive()) {
            return false;
        }
        // Movement skills do not need a target: they act on the pet itself.
        if ("sky_spiral".equalsIgnoreCase(skillName) || "spiral".equalsIgnoreCase(skillName)
            || "sky".equalsIgnoreCase(skillName) || "fly".equalsIgnoreCase(skillName)) {
            boolean released = mod.getPathfindingManager() != null && mod.getPathfindingManager().forceSkySpiral(pet);
            if (released) notifySkillTriggered(ownerUuid, "sky_spiral", SKILL_DISPLAY_TICKS);
            return released;
        }
        LivingEntity target = getCurrentTarget(ownerUuid);
        if (target == null || !target.isAlive() || target.removed) {
            target = owner != null ? mod.findNearestTarget(owner, pet) : null;
        }
        if (target == null) {
            return false;
        }
        setTarget(ownerUuid, pet, target);
        boolean released = executeNamedAttack(level, ownerUuid, pet, owner, target, skillName, false, true);
        if (released) lastSpecialAttackTick.put(ownerUuid, mod.getServerTicks());
        return released;
    }

    /** Periodic bookkeeping: drop targets whose pet or victim disappeared. */
    public void tick() {
        Iterator<Map.Entry<UUID, Long>> skillIt = currentSkillEndTick.entrySet().iterator();
        while (skillIt.hasNext()) {
            Map.Entry<UUID, Long> entry = skillIt.next();
            if (entry.getValue() <= mod.getServerTicks()) {
                currentSkillIds.remove(entry.getKey());
                skillIt.remove();
            }
        }
        // Iterator instead of a defensive ArrayList copy: this runs on every server tick.
        Iterator<Map.Entry<UUID, LivingEntity>> it = currentTargets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, LivingEntity> entry = it.next();
            LivingEntity target = entry.getValue();
            Cow pet = mod.getActivePet(entry.getKey());
            if (target == null || !target.isAlive() || target.removed || pet == null || !pet.isAlive()) {
                if (target != null) {
                    if (target.isAlive()) {
                        target.setGlowing(false);
                    }
                    UUID targetId = target.getUUID();
                    targetTickSnapshots.remove(targetId);
                    targetStuckSinceTick.remove(targetId);
                    untickableTargets.remove(targetId);
                    untickableSnapshots.remove(targetId);
                }
                evasionSteps.remove(entry.getKey());
                pathfindingManager.stopCombatStep(pet);
                it.remove();
            }
        }
    }

    public void cleanupAll() {
        for (UUID ownerUuid : new ArrayList<>(currentTargets.keySet())) {
            clearTarget(ownerUuid);
        }
        currentTargets.clear();
        nextAttackTick.clear();
        evasionSteps.clear();
        lastSpecialAttackTick.clear();
        currentSkillIds.clear();
        currentSkillEndTick.clear();
        rageEndTick.clear();
        summonManager.cleanupAll();
        MinionManager.cleanupAll();
        SpecialAttackManagers.cleanupAll();
        SkillCleanup.cleanupAll();
        pathfindingManager.cleanupAll();
    }

    public SummonManager getSummonManager() {
        return summonManager;
    }
}
