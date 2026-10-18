package net.dabbit.supercow.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.combat.CustomProjectiles.ArrowEffectType;
import net.dabbit.supercow.combat.CustomProjectiles.SuperCowArrow;
import net.dabbit.supercow.config.SuperCowConfig;
import net.dabbit.supercow.pathfinding.PathfindingManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Manages summoned helper cows (FOLLOW, AGGRESSIVE, DEFENSIVE, GUARD).
 * Fixes:
 * 1. consumeHayBale had an unconditional "return true;" on line 1, making hay bale consumption dead code.
 * 2. ATTACK_COOLDOWN was 1 tick (20 arrows/sec per summon), now conservative 40 ticks (2.0s).
 * 3. Friendly-fire check on summon arrows is now active via SuperCowArrow + TargetValidator.
 */
public class SummonManager {
    public enum SummonMode {
        FOLLOW("跟随模式"),
        AGGRESSIVE("主动攻击"),
        DEFENSIVE("防御模式"),
        GUARD("守卫模式");

        private final String displayName;

        SummonMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static SummonMode fromString(String name) {
            if (name == null) {
                return DEFENSIVE;
            }
            for (SummonMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return DEFENSIVE;
        }
    }

    public static class SummonedCowState {
        public final Cow cow;
        public final UUID ownerUuid;
        public final Cow parentPet;
        public final boolean rageMode;
        public final long spawnTick;
        public final int maxLifetimeTicks;
        public final Vec3 guardOrigin;
        public SummonMode mode;
        public LivingEntity currentTarget;
        public long lastAttackTick;

        public SummonedCowState(
            Cow cow,
            UUID ownerUuid,
            Cow parentPet,
            boolean rageMode,
            long spawnTick,
            int maxLifetimeTicks,
            SummonMode mode
        ) {
            this.cow = cow;
            this.ownerUuid = ownerUuid;
            this.parentPet = parentPet;
            this.rageMode = rageMode;
            this.spawnTick = spawnTick;
            this.maxLifetimeTicks = maxLifetimeTicks;
            this.guardOrigin = cow.position();
            this.mode = mode;
            this.lastAttackTick = spawnTick;
        }
    }

    private final SuperCowMod mod;
    private final PathfindingManager pathfindingManager;
    private final Map<UUID, List<SummonedCowState>> ownerSummons = new HashMap<>();
    private final Map<UUID, Long> lastSummonTickByOwner = new HashMap<>();
    private final Map<UUID, SummonMode> ownerModes = new HashMap<>();

    public SummonManager(SuperCowMod mod, PathfindingManager pathfindingManager) {
        this.mod = mod;
        this.pathfindingManager = pathfindingManager;
    }

    /**
     * FIXED BUG: Previously had "return true" on the first line so hay bales were never checked or consumed.
     */
    public boolean consumeHayBale(ServerPlayer player, boolean requireItem) {
        if (!requireItem || player.isCreative()) {
            return true;
        }
        for (int i = 0; i < player.inventory.getContainerSize(); i++) {
            ItemStack stack = player.inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.HAY_BLOCK) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    player.inventory.setItem(i, ItemStack.EMPTY);
                }
                return true;
            }
        }
        return false;
    }

    public boolean summonHelperCows(
        ServerLevel level,
        ServerPlayer owner,
        Cow parentPet,
        LivingEntity initialTarget,
        boolean rageMode,
        boolean bypassCooldownAndCost
    ) {
        SuperCowConfig cfg = mod.getConfig();
        UUID ownerUuid = owner.getUUID();
        long nowTick = mod.getServerTicks();

        if (!bypassCooldownAndCost) {
            Long lastTick = lastSummonTickByOwner.get(ownerUuid);
            if (lastTick != null && (nowTick - lastTick) < cfg.summonCooldownTicks) {
                long remainSec = (cfg.summonCooldownTicks - (nowTick - lastTick)) / 20L + 1L;
                owner.sendMessage(new TextComponent("§c[超级牛] 小牛召唤冷却中，还需 " + remainSec + " 秒！"), ownerUuid);
                return false;
            }
            if (!consumeHayBale(owner, cfg.consumeHayBaleForSummon)) {
                owner.sendMessage(new TextComponent("§c[超级牛] 背包中没有干草块 (Hay Bale)，无法召唤护卫小牛！"), ownerUuid);
                return false;
            }
        }

        // Remove any previous summons for this owner before spawning fresh ones
        cleanupSummonsForOwner(ownerUuid);

        int count = rageMode ? cfg.rageSummonCount : cfg.normalSummonCount;
        double maxHp = rageMode ? cfg.rageSummonHealth : cfg.summonHealth;
        SummonMode mode = ownerModes.getOrDefault(ownerUuid, SummonMode.DEFENSIVE);
        List<SummonedCowState> list = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Cow summon = EntityType.COW.create(level);
            if (summon == null) {
                continue;
            }
            double angle = (2.0D * Math.PI * i) / Math.max(1, count);
            double sx = parentPet.getX() + Math.cos(angle) * 2.0D;
            double sy = parentPet.getY();
            double sz = parentPet.getZ() + Math.sin(angle) * 2.0D;
            summon.moveTo(sx, sy, sz, parentPet.yRot, 0.0F);

            AttributeInstance hpAttr = summon.getAttribute(Attributes.MAX_HEALTH);
            if (hpAttr != null) {
                hpAttr.setBaseValue(maxHp);
            }
            summon.setHealth((float) maxHp);
            summon.setCustomName(new TextComponent(
                (rageMode ? "§c§l狂暴护卫牛 #" : "§a护卫小牛 #") + (i + 1) + " §7[" + mode.getDisplayName() + "]"
            ));
            summon.setCustomNameVisible(true);
            summon.addTag("supercow_summon");
            summon.addTag("supercow_owner:" + ownerUuid.toString());

            if (rageMode) {
                summon.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, cfg.summonLifetimeTicks, 0, false, false));
                summon.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, cfg.summonLifetimeTicks, 0, false, false));
            }

            level.addFreshEntity(summon);
            SummonedCowState state = new SummonedCowState(summon, ownerUuid, parentPet, rageMode, nowTick, cfg.summonLifetimeTicks, mode);
            if (initialTarget != null && TargetValidator.isValidCombatTarget(initialTarget, ownerUuid, summon, true)) {
                state.currentTarget = initialTarget;
            }
            list.add(state);
        }

        ownerSummons.put(ownerUuid, list);
        lastSummonTickByOwner.put(ownerUuid, nowTick);

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, parentPet.getX(), parentPet.getY() + 1.0D, parentPet.getZ(), 12, 0.8D, 0.5D, 0.8D, 0.05D);
        level.playSound(null, parentPet.getX(), parentPet.getY(), parentPet.getZ(), SoundEvents.COW_AMBIENT, SoundSource.NEUTRAL, 0.9F, 1.2F);
        owner.sendMessage(new TextComponent("§a[超级牛] 已召唤 " + list.size() + " 只护卫小牛（持续 " + (cfg.summonLifetimeTicks / 20) + " 秒）！"), ownerUuid);
        return true;
    }

    public void setSummonMode(UUID ownerUuid, SummonMode mode) {
        ownerModes.put(ownerUuid, mode);
        List<SummonedCowState> list = ownerSummons.get(ownerUuid);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                SummonedCowState state = list.get(i);
                state.mode = mode;
                if (state.cow != null && state.cow.isAlive()) {
                    state.cow.setCustomName(new TextComponent(
                        (state.rageMode ? "§c§l狂暴护卫牛 #" : "§a护卫小牛 #") + (i + 1) + " §7[" + mode.getDisplayName() + "]"
                    ));
                }
            }
        }
    }

    public SummonMode getSummonMode(UUID ownerUuid) {
        return ownerModes.getOrDefault(ownerUuid, SummonMode.DEFENSIVE);
    }

    public List<SummonedCowState> getActiveSummons(UUID ownerUuid) {
        return ownerSummons.getOrDefault(ownerUuid, new ArrayList<>());
    }

    public void notifyOwnerOrPetAttacked(UUID ownerUuid, LivingEntity attacker) {
        List<SummonedCowState> list = ownerSummons.get(ownerUuid);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SummonedCowState state : list) {
            if (state.cow != null && state.cow.isAlive() && TargetValidator.isValidCombatTarget(attacker, ownerUuid, state.cow, true)) {
                state.currentTarget = attacker;
            }
        }
    }

    public void tick() {
        long nowTick = mod.getServerTicks();
        SuperCowConfig cfg = mod.getConfig();

        Iterator<Map.Entry<UUID, List<SummonedCowState>>> mapIt = ownerSummons.entrySet().iterator();
        while (mapIt.hasNext()) {
            Map.Entry<UUID, List<SummonedCowState>> entry = mapIt.next();
            List<SummonedCowState> list = entry.getValue();
            Iterator<SummonedCowState> listIt = list.iterator();
            while (listIt.hasNext()) {
                SummonedCowState state = listIt.next();
                Cow cow = state.cow;
                if (cow == null || !cow.isAlive() || cow.removed) {
                    if (cow != null) {
                        pathfindingManager.stopPathfinding(cow.getUUID());
                    }
                    listIt.remove();
                    continue;
                }
                if ((nowTick - state.spawnTick) >= state.maxLifetimeTicks) {
                    if (cow.level instanceof ServerLevel) {
                        ((ServerLevel) cow.level).sendParticles(ParticleTypes.CLOUD, cow.getX(), cow.getY() + 0.5D, cow.getZ(), 6, 0.3D, 0.3D, 0.3D, 0.02D);
                    }
                    pathfindingManager.stopPathfinding(cow);
                    cow.remove();
                    listIt.remove();
                    continue;
                }

                tickSummonedCowBehavior(state, nowTick, cfg);
            }
            if (list.isEmpty()) {
                mapIt.remove();
            }
        }
    }

    private void tickSummonedCowBehavior(SummonedCowState state, long nowTick, SuperCowConfig cfg) {
        Cow cow = state.cow;
        if (!(cow.level instanceof ServerLevel)) {
            return;
        }
        ServerLevel level = (ServerLevel) cow.level;

        // Validate current target
        if (state.currentTarget != null) {
            if (!TargetValidator.isValidCombatTarget(state.currentTarget, state.ownerUuid, cow, true)
                || cow.distanceTo(state.currentTarget) > cfg.maxTargetDistance) {
                state.currentTarget = null;
            }
        }

        // Acquire target according to mode
        if (state.currentTarget == null && state.mode != SummonMode.FOLLOW) {
            LivingEntity mainTarget = mod.getCombatManager().getCurrentTarget(state.ownerUuid);
            if (mainTarget != null && TargetValidator.isValidCombatTarget(mainTarget, state.ownerUuid, cow, true)) {
                state.currentTarget = mainTarget;
            } else if (state.mode == SummonMode.AGGRESSIVE || state.mode == SummonMode.GUARD) {
                double searchRange = state.mode == SummonMode.GUARD ? 6.0D : cfg.summonTargetRange;
                AABB box = cow.getBoundingBox().inflate(searchRange);
                double bestDist = Double.MAX_VALUE;
                for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box)) {
                    if (!TargetValidator.isValidCombatTarget(candidate, state.ownerUuid, cow, false)) {
                        continue;
                    }
                    double d = cow.distanceToSqr(candidate);
                    if (d < bestDist) {
                        bestDist = d;
                        state.currentTarget = candidate;
                    }
                }
            }
        }

        // Execute attack or movement
        if (state.currentTarget != null && state.mode != SummonMode.FOLLOW) {
            PathfindingManager.faceEntity(cow, state.currentTarget);
            double dist = cow.distanceTo(state.currentTarget);
            if (dist > 5.0D) {
                pathfindingManager.moveToEntity(cow, state.currentTarget, 1.15D);
            } else {
                pathfindingManager.stopPathfinding(cow);
            }

            if ((nowTick - state.lastAttackTick) >= cfg.summonAttackCooldownTicks && dist <= cfg.summonTargetRange) {
                state.lastAttackTick = nowTick;
                double dmg = state.rageMode ? cfg.rageSummonArrowDamage : cfg.summonArrowDamage;
                SuperCowArrow arrow = new SuperCowArrow(
                    level,
                    cow,
                    state.ownerUuid,
                    dmg,
                    state.rageMode,
                    true,
                    ArrowEffectType.NONE,
                    0
                );
                Vec3 from = new Vec3(cow.getX(), cow.getEyeY() - 0.1D, cow.getZ());
                Vec3 to = new Vec3(state.currentTarget.getX(), state.currentTarget.getY() + state.currentTarget.getBbHeight() * 0.5D, state.currentTarget.getZ());
                Vec3 dir = to.subtract(from);
                double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
                arrow.shoot(dir.x, dir.y + horiz * 0.15D, dir.z, 1.4F, 3.0F);
                level.addFreshEntity(arrow);
                level.playSound(null, cow.getX(), cow.getY(), cow.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 0.5F, 1.2F);
            }
        } else {
            // Follow parent pet or guard origin
            if (state.mode == SummonMode.GUARD) {
                if (cow.position().distanceTo(state.guardOrigin) > 2.5D) {
                    pathfindingManager.moveToPosition(cow, state.guardOrigin, 1.05D);
                }
            } else if (state.parentPet != null && state.parentPet.isAlive()) {
                double dist = cow.distanceTo(state.parentPet);
                if (dist > cfg.summonFollowDistance + 1.5D) {
                    pathfindingManager.moveToEntity(cow, state.parentPet, 1.15D);
                }
            }
        }
    }

    public void cleanupSummonsForOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return;
        }
        List<SummonedCowState> list = ownerSummons.remove(ownerUuid);
        if (list != null) {
            for (SummonedCowState state : list) {
                if (state.cow != null && !state.cow.removed) {
                    pathfindingManager.stopPathfinding(state.cow);
                    state.cow.remove();
                }
            }
        }
    }

    public void cleanupAll() {
        for (List<SummonedCowState> list : ownerSummons.values()) {
            for (SummonedCowState state : list) {
                if (state.cow != null && !state.cow.removed) {
                    pathfindingManager.stopPathfinding(state.cow);
                    state.cow.remove();
                }
            }
        }
        ownerSummons.clear();
        lastSummonTickByOwner.clear();
    }
}
