package net.dabbit.supercow.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.config.SuperCowConfig;
import net.dabbit.supercow.data.PetData;
import net.dabbit.supercow.pathfinding.PathfindingManager;
import net.minecraft.core.particles.ParticleTypes;
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

    private final Map<UUID, PetMode> petModes = new HashMap<>();
    private final Map<UUID, LivingEntity> currentTargets = new HashMap<>();
    private final Map<UUID, Long> lastAttackTick = new HashMap<>();
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
        LivingEntity prev = currentTargets.remove(ownerUuid);
        if (prev != null && prev.isAlive()) {
            prev.setGlowing(false);
        }
        // FIXED LEAK: the per-target guard maps were never pruned, so every entity the pet ever
        // attacked stayed referenced (tick snapshots, stuck timers, blacklist entries) forever.
        if (prev != null) {
            UUID id = prev.getUUID();
            targetTickSnapshots.remove(id);
            targetStuckSinceTick.remove(id);
            untickableTargets.remove(id);
            untickableSnapshots.remove(id);
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

        if (dist > cfg.closeRangeDistance) {
            pathfindingManager.moveToEntity(pet, target, moveSpeed);
        } else {
            pathfindingManager.stopPathfinding(pet);
        }

        int cooldown = rage ? cfg.rageAttackCooldownTicks : cfg.attackCooldownTicks;
        long lastAtk = lastAttackTick.getOrDefault(ownerUuid, 0L);
        if ((nowTick - lastAtk) < cooldown) {
            return;
        }
        lastAttackTick.put(ownerUuid, nowTick);

        performCombatAction(level, ownerUuid, pet, owner, target, data, dist, rage, nowTick, cfg);
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

    private void followOwnerIfNeeded(Cow pet, ServerPlayer owner, SuperCowConfig cfg) {
        if (owner == null || !owner.isAlive() || owner.level != pet.level) {
            return;
        }
        double dist = pet.distanceTo(owner);
        double follow = Math.max(2.0D, cfg.followDistance);
        double stop = Math.max(0.5D, cfg.followStopDistance);
        // Hysteresis: with a single threshold the pet would start walking the instant it drifted
        // 0.01 block past the line and stop as soon as it crossed back, which reads as the pet
        // shuffling in place right next to the player.
        double resume = Math.max(follow, stop) + Math.max(0.0D, cfg.followStartBuffer);
        if (dist > cfg.teleportDistance) {
            pathfindingManager.stopPathfinding(pet);
            pet.teleportTo(owner.getX() + 1.5D, owner.getY(), owner.getZ() + 1.5D);
        } else if (pathfindingManager.hasActivePath(pet.getUUID())) {
            // Already walking towards the owner: hold the line until the stand-off distance.
            if (dist <= stop) {
                pathfindingManager.stopPathfinding(pet);
            }
        } else if (dist > resume) {
            pathfindingManager.moveToEntity(pet, owner, 1.2D);
        }
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
        boolean canSpecial = (nowTick - lastSpecial) >= cfg.specialAttackCooldownTicks;
        double specialProb = rage ? cfg.rageSpecialAttackChance : cfg.specialAttackChance;

        if (canSpecial && random.nextDouble() < specialProb) {
            String chosen = cfg.legacySkillsEnabled ? rollWeightedSkill(rage) : chooseSpecialAttackByDistance(dist, cfg);
            lastSpecialAttackTick.put(ownerUuid, nowTick);
            executeNamedAttack(level, ownerUuid, pet, owner, target, chosen, rage, false);
            return;
        }

        if (dist <= cfg.closeRangeDistance + 0.5D) {
            performMeleeAttack(level, ownerUuid, pet, target, data, rage, cfg);
        } else {
            SpecialAttackManagers.shootArrows(level, pet, ownerUuid, target, rage);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Original skill table (CombatManager.kt:549-657). The Bukkit plugin did NOT pick a skill by
    // range: it rolled a single weighted table for every attack, so a close-range pet could still
    // launch an exploding cow and a long-range pet could start a music attack. That unpredictability
    // is restored here; the distance pools below remain as a fallback when the legacy table is off.
    // -------------------------------------------------------------------------------------------
    // The table itself lives in Skills (weights normal/rage). Only "rollable" skills take part:
    // sky_spiral is exclusive to its own trigger, and anything the owner disabled in the GUI is
    // skipped, so a switched-off skill can never be drawn.
    private String rollWeightedSkill(boolean rage) {
        SuperCowConfig cfg = mod.getConfig();
        java.util.List<Skills.Skill> pool = new java.util.ArrayList<>();
        int total = 0;
        for (Skills.Skill skill : Skills.all()) {
            if (!skill.rollable || !cfg.isSkillEnabled(skill.id)) {
                continue;
            }
            int w = rage ? skill.weightRage : skill.weightNormal;
            if (w <= 0) {
                continue;
            }
            pool.add(skill);
            total += w;
        }
        if (total <= 0) {
            return "arrow";
        }
        int roll = random.nextInt(total);
        for (Skills.Skill skill : pool) {
            int w = rage ? skill.weightRage : skill.weightNormal;
            if (roll < w) {
                return skill.id;
            }
            roll -= w;
        }
        return "arrow";
    }

    private String chooseSpecialAttackByDistance(double dist, SuperCowConfig cfg) {
        if (dist <= cfg.closeRangeDistance) {
            String[] pool = new String[]{"music", "potion", "summon", "arrow"};
            return pool[random.nextInt(pool.length)];
        } else if (dist <= cfg.mediumRangeDistance) {
            String[] pool = new String[]{"arrow", "fireball", "potion", "music", "summon"};
            return pool[random.nextInt(pool.length)];
        } else {
            String[] pool = new String[]{"arrow", "firework", "chicken", "exploding_cow"};
            return pool[random.nextInt(pool.length)];
        }
    }

    public boolean executeNamedAttack(
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
                target.push((dx / len) * 0.25D, 0.15D, (dz / len) * 0.25D);
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
        lastAttackTick.remove(ownerUuid);
        lastSpecialAttackTick.remove(ownerUuid);
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
            return mod.getPathfindingManager() != null && mod.getPathfindingManager().forceSkySpiral(pet);
        }
        LivingEntity target = getCurrentTarget(ownerUuid);
        if (target == null || !target.isAlive() || target.removed) {
            target = owner != null ? mod.findNearestTarget(owner, pet) : null;
        }
        if (target == null) {
            return false;
        }
        setTarget(ownerUuid, pet, target);
        lastSpecialAttackTick.put(ownerUuid, mod.getServerTicks());
        return executeNamedAttack(level, ownerUuid, pet, owner, target, skillName, false, true);
    }

    /** Periodic bookkeeping: drop targets whose pet or victim disappeared. */
    public void tick() {
        for (UUID ownerUuid : new java.util.ArrayList<>(currentTargets.keySet())) {
            LivingEntity target = currentTargets.get(ownerUuid);
            Cow pet = mod.getActivePet(ownerUuid);
            if (target == null || !target.isAlive() || target.removed || pet == null || !pet.isAlive()) {
                clearTarget(ownerUuid);
            }
        }
    }

    public void cleanupAll() {
        for (UUID ownerUuid : new ArrayList<>(currentTargets.keySet())) {
            clearTarget(ownerUuid);
        }
        currentTargets.clear();
        lastAttackTick.clear();
        lastSpecialAttackTick.clear();
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
