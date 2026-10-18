package net.dabbit.supercow.combat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.config.SuperCowConfig;
import net.dabbit.supercow.pathfinding.PathfindingManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.phys.Vec3;

/**
 * The two small summons from the original plugin: a flock of parrots and a pack of rabbits.
 *
 * <p>Both are deliberately janky: parrots have no AI and no gravity, so they are puppeted by
 * writing a velocity towards an orbiting point around the pet every tick; rabbits hop straight
 * at whatever the pet is fighting. They are tracked by SkillCleanup so they can never outlive
 * their lifetime, the pet's death, or a config reload.</p>
 */
public final class MinionManager {
    private static final Random RANDOM = new Random();
    private static final List<Minion> MINIONS = new ArrayList<>();

    private enum Kind {
        PARROT,
        RABBIT
    }

    private static final class Minion {
        final Mob entity;
        final UUID ownerUuid;
        final Kind kind;
        final double damage;
        final boolean rage;
        final long spawnTick;
        final int lifetimeTicks;
        Cow parentPet;
        LivingEntity target;
        long lastAttackTick;
        double orbitPhase;

        Minion(Mob entity, UUID ownerUuid, Kind kind, double damage, boolean rage, long spawnTick, int lifetimeTicks, Cow parentPet, LivingEntity target) {
            this.entity = entity;
            this.ownerUuid = ownerUuid;
            this.kind = kind;
            this.damage = damage;
            this.rage = rage;
            this.spawnTick = spawnTick;
            this.lifetimeTicks = lifetimeTicks;
            this.parentPet = parentPet;
            this.target = target;
            this.lastAttackTick = spawnTick;
            this.orbitPhase = RANDOM.nextDouble() * Math.PI * 2.0D;
        }
    }

    private MinionManager() {
    }

    private static SuperCowConfig config() {
        SuperCowMod mod = SuperCowMod.getInstance();
        return mod == null ? null : mod.getConfig();
    }

    private static long nowTick() {
        return SuperCowMod.getServerTicksStatic();
    }

    // ------------------------------------------------------------------ spawning

    public static void spawnParrots(ServerLevel level, ServerPlayer owner, Cow parentPet, LivingEntity target, boolean rage) {
        SuperCowConfig cfg = config();
        if (cfg == null || level == null || parentPet == null) {
            return;
        }
        UUID ownerUuid = owner != null ? owner.getUUID() : SuperCowMod.getOwnerUuidFromTags(parentPet);
        if (ownerUuid == null) {
            return;
        }
        int count = rage ? cfg.rageParrotSummonCount : cfg.parrotSummonCount;
        long tick = nowTick();
        for (int i = 0; i < count; i++) {
            Parrot parrot = EntityType.PARROT.create(level);
            if (parrot == null) {
                continue;
            }
            double angle = (Math.PI * 2.0D * i) / Math.max(1, count);
            parrot.moveTo(
                parentPet.getX() + Math.cos(angle) * 1.2D,
                parentPet.getY() + 1.6D,
                parentPet.getZ() + Math.sin(angle) * 1.2D,
                parentPet.yRot,
                0.0F
            );
            parrot.setVariant(RANDOM.nextInt(5));
            parrot.setNoAi(true);
            parrot.setNoGravity(true);
            parrot.setInvulnerable(true);
            parrot.setCustomName(new TextComponent(rage ? "§c§l狂暴鹦鹉 #" + (i + 1) : "§d§l鹦鹉护卫 #" + (i + 1)));
            parrot.setCustomNameVisible(false);
            parrot.addTag("supercow_summon");
            parrot.addTag("supercow_minion");
            parrot.addTag("supercow_owner:" + ownerUuid);
            level.addFreshEntity(parrot);
            SkillCleanup.track(parrot, ownerUuid, cfg.birdieLifetimeTicks, "parrot");
            MINIONS.add(new Minion(parrot, ownerUuid, Kind.PARROT, cfg.parrotPeckDamage, rage, tick, cfg.birdieLifetimeTicks, parentPet, target));
        }
        if (count > 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, parentPet.getX(), parentPet.getY() + 1.5D, parentPet.getZ(), 10, 0.6D, 0.4D, 0.6D, 0.05D);
            level.playSound(null, parentPet.getX(), parentPet.getY(), parentPet.getZ(), SoundEvents.PARROT_AMBIENT, SoundSource.NEUTRAL, 0.8F, 1.3F);
        }
    }

    public static void spawnRabbits(ServerLevel level, ServerPlayer owner, Cow parentPet, LivingEntity target, boolean rage) {
        SuperCowConfig cfg = config();
        if (cfg == null || level == null || parentPet == null) {
            return;
        }
        UUID ownerUuid = owner != null ? owner.getUUID() : SuperCowMod.getOwnerUuidFromTags(parentPet);
        if (ownerUuid == null) {
            return;
        }
        int count = rage ? cfg.rageRabbitSummonCount : cfg.rabbitSummonCount;
        long tick = nowTick();
        for (int i = 0; i < count; i++) {
            Rabbit rabbit = EntityType.RABBIT.create(level);
            if (rabbit == null) {
                continue;
            }
            double angle = (Math.PI * 2.0D * i) / Math.max(1, count);
            rabbit.moveTo(
                parentPet.getX() + Math.cos(angle) * 1.4D,
                parentPet.getY(),
                parentPet.getZ() + Math.sin(angle) * 1.4D,
                parentPet.yRot,
                0.0F
            );
            rabbit.setRabbitType(rage ? 99 : 2);
            rabbit.setNoAi(true);
            rabbit.setInvulnerable(true);
            rabbit.setCustomName(new TextComponent(rage ? "§c§l狂暴兔子 #" + (i + 1) : "§f§l兔子护卫 #" + (i + 1)));
            rabbit.setCustomNameVisible(false);
            rabbit.addTag("supercow_summon");
            rabbit.addTag("supercow_minion");
            rabbit.addTag("supercow_owner:" + ownerUuid);
            level.addFreshEntity(rabbit);
            SkillCleanup.track(rabbit, ownerUuid, cfg.birdieLifetimeTicks, "rabbit");
            MINIONS.add(new Minion(rabbit, ownerUuid, Kind.RABBIT, cfg.rabbitBiteDamage, rage, tick, cfg.birdieLifetimeTicks, parentPet, target));
        }
        if (count > 0) {
            level.sendParticles(ParticleTypes.CLOUD, parentPet.getX(), parentPet.getY() + 0.3D, parentPet.getZ(), 10, 0.6D, 0.2D, 0.6D, 0.05D);
            level.playSound(null, parentPet.getX(), parentPet.getY(), parentPet.getZ(), SoundEvents.RABBIT_AMBIENT, SoundSource.NEUTRAL, 0.8F, 1.3F);
        }
    }

    // ------------------------------------------------------------------ ticking

    public static void tickAll() {
        if (MINIONS.isEmpty()) {
            return;
        }
        SuperCowConfig cfg = config();
        if (cfg == null) {
            return;
        }
        long tick = nowTick();
        Iterator<Minion> it = MINIONS.iterator();
        while (it.hasNext()) {
            Minion minion = it.next();
            Mob mob = minion.entity;
            if (mob == null || mob.removed || !mob.isAlive()) {
                if (mob != null) {
                    SkillCleanup.untrack(mob);
                }
                it.remove();
                continue;
            }
            if (tick - minion.spawnTick > minion.lifetimeTicks) {
                despawn(mob);
                SkillCleanup.untrack(mob);
                it.remove();
                continue;
            }
            if (minion.target != null && (!minion.target.isAlive() || minion.target.removed)) {
                minion.target = null;
            }
            if (minion.parentPet == null || !minion.parentPet.isAlive() || minion.parentPet.removed) {
                minion.parentPet = null;
            }
            if (minion.kind == Kind.PARROT) {
                tickParrot(minion, cfg, tick);
            } else {
                tickRabbit(minion, cfg, tick);
            }
        }
    }

    private static void tickParrot(Minion minion, SuperCowConfig cfg, long tick) {
        Mob parrot = minion.entity;
        Vec3 anchor;
        if (minion.target != null) {
            anchor = minion.target.position().add(0.0D, minion.target.getBbHeight() * 0.9D, 0.0D);
        } else if (minion.parentPet != null) {
            anchor = minion.parentPet.position().add(0.0D, 1.7D, 0.0D);
        } else {
            anchor = parrot.position();
        }
        double angle = minion.orbitPhase + tick * 0.18D;
        double radius = minion.target != null ? 1.5D : 1.9D;
        Vec3 wanted = new Vec3(
            anchor.x + Math.cos(angle) * radius,
            anchor.y + 0.33D + Math.sin(angle * 2.0D) * 0.25D,
            anchor.z + Math.sin(angle) * radius
        );
        Vec3 diff = wanted.subtract(parrot.position());
        Vec3 vel = diff.scale(0.35D);
        if (vel.length() > 0.75D) {
            vel = vel.normalize().scale(0.75D);
        }
        parrot.setDeltaMovement(vel);
        if (minion.target != null) {
            PathfindingManager.faceEntity(parrot, minion.target);
        }
        tryPeck(minion, cfg, tick, 2.4D);
    }

    private static void tickRabbit(Minion minion, SuperCowConfig cfg, long tick) {
        Mob rabbit = minion.entity;
        LivingEntity desired;
        if (minion.target != null) {
            desired = minion.target;
        } else if (minion.parentPet != null) {
            desired = minion.parentPet;
        } else {
            return;
        }
        double dist = rabbit.distanceTo(desired);
        if (dist > 1.6D && rabbit.isOnGround() && tick % 8 == 0) {
            Vec3 dir = desired.position().subtract(rabbit.position());
            Vec3 flat = new Vec3(dir.x, 0.0D, dir.z);
            if (flat.lengthSqr() > 1.0E-4D) {
                flat = flat.normalize().scale(0.34D);
            }
            rabbit.setDeltaMovement(flat.x, 0.42D, flat.z);
        }
        PathfindingManager.faceEntity(rabbit, desired);
        tryPeck(minion, cfg, tick, 1.9D);
    }

    private static void tryPeck(Minion minion, SuperCowConfig cfg, long tick, double reach) {
        LivingEntity target = minion.target;
        if (target == null) {
            return;
        }
        if (tick - minion.lastAttackTick < cfg.minionAttackCooldownTicks) {
            return;
        }
        if (minion.entity.distanceTo(target) > reach) {
            return;
        }
        if (!TargetValidator.isValidCombatTarget(target, minion.ownerUuid, minion.entity, true)) {
            minion.target = null;
            return;
        }
        minion.lastAttackTick = tick;
        Entity source = minion.parentPet != null ? minion.parentPet : minion.entity;
        SuperCowMod.recordDamageOwner(target.getUUID(), minion.ownerUuid);
        target.hurt(DamageSource.mobAttack((LivingEntity) source), (float) minion.damage);
        if (minion.entity.level instanceof ServerLevel) {
            ((ServerLevel) minion.entity.level).sendParticles(
                ParticleTypes.CRIT,
                target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                5, 0.2D, 0.2D, 0.2D, 0.05D
            );
        }
        if (minion.kind == Kind.PARROT) {
            minion.entity.level.playSound(null, minion.entity.getX(), minion.entity.getY(), minion.entity.getZ(),
                SoundEvents.PARROT_HURT, SoundSource.NEUTRAL, 0.35F, 1.8F);
        } else {
            minion.entity.level.playSound(null, minion.entity.getX(), minion.entity.getY(), minion.entity.getZ(),
                SoundEvents.RABBIT_ATTACK, SoundSource.NEUTRAL, 0.35F, 1.6F);
        }
    }

    // ------------------------------------------------------------------ cleanup

    private static void despawn(Mob mob) {
        if (mob.level instanceof ServerLevel) {
            ((ServerLevel) mob.level).sendParticles(ParticleTypes.CLOUD, mob.getX(), mob.getY() + 0.3D, mob.getZ(), 5, 0.25D, 0.25D, 0.25D, 0.02D);
        }
        mob.remove();
    }

    public static void cleanupForOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return;
        }
        Iterator<Minion> it = MINIONS.iterator();
        while (it.hasNext()) {
            Minion minion = it.next();
            if (ownerUuid.equals(minion.ownerUuid)) {
                if (minion.entity != null && !minion.entity.removed) {
                    despawn(minion.entity);
                }
                it.remove();
            }
        }
    }

    public static void cleanupAll() {
        for (Minion minion : MINIONS) {
            if (minion.entity != null && !minion.entity.removed) {
                minion.entity.remove();
            }
        }
        MINIONS.clear();
    }

    public static int countForOwner(UUID ownerUuid) {
        int n = 0;
        for (Minion minion : MINIONS) {
            if (ownerUuid != null && ownerUuid.equals(minion.ownerUuid) && minion.entity != null && !minion.entity.removed) {
                n++;
            }
        }
        return n;
    }

    public static int countAll() {
        return MINIONS.size();
    }
}
