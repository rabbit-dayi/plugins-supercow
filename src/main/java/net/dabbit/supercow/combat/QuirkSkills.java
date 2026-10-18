package net.dabbit.supercow.combat;

import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.config.SuperCowConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The ten "abstract" skills added on top of the original 1.19.4 roster.
 *
 * Every skill either finishes inside {@code quirkSkillDurationTicks} or spawns entities that are
 * registered in {@link SkillCleanup}, so nothing is left behind in the world (hard requirement).
 * Damage is deliberately low; these are flavour skills, not damage skills.
 */
public final class QuirkSkills {

    private static final List<Task> TASKS = new ArrayList<>();

    private QuirkSkills() {
    }

    public static boolean isQuirkSkill(String skillId) {
        return skillId != null && (
            skillId.equalsIgnoreCase("cow_rain")
                || skillId.equalsIgnoreCase("milk_bomb")
                || skillId.equalsIgnoreCase("moon_walk")
                || skillId.equalsIgnoreCase("moo_wave")
                || skillId.equalsIgnoreCase("hay_tornado")
                || skillId.equalsIgnoreCase("butterfly_swarm")
                || skillId.equalsIgnoreCase("zero_gravity")
                || skillId.equalsIgnoreCase("mirror_cow")
                || skillId.equalsIgnoreCase("teleport_swap")
                || skillId.equalsIgnoreCase("confetti"));
    }

    public static boolean cast(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, ServerPlayer owner, String skillId) {
        if (level == null || pet == null || ownerUuid == null || skillId == null) {
            return false;
        }
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        int dur = Math.max(20, cfg.quirkSkillDurationTicks);
        switch (skillId.toLowerCase()) {
            case "cow_rain":
                return cowRain(level, pet, ownerUuid, target, rage, cfg, dur);
            case "milk_bomb":
                return milkBomb(level, pet, ownerUuid, target, rage, cfg);
            case "moon_walk":
                return moonWalk(level, pet, ownerUuid, cfg, dur);
            case "moo_wave":
                return mooWave(level, pet, ownerUuid, target, rage, cfg, dur);
            case "hay_tornado":
                return hayTornado(level, pet, ownerUuid, target, rage, cfg, dur);
            case "butterfly_swarm":
                return butterflySwarm(level, pet, ownerUuid, target, rage, cfg, dur);
            case "zero_gravity":
                return zeroGravity(level, pet, ownerUuid, cfg, dur);
            case "mirror_cow":
                return mirrorCow(level, pet, ownerUuid, cfg, dur);
            case "teleport_swap":
                return teleportSwap(level, pet, ownerUuid, target, cfg);
            case "confetti":
                return confetti(level, pet, ownerUuid, target, rage, cfg);
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ skills

    /** 奶牛雨：几只牛从天上掉下来砸一下，落地就化作烟。 */
    private static boolean cowRain(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, SuperCowConfig cfg, int dur) {
        if (target == null) {
            return false;
        }
        int count = Math.max(1, cfg.cowRainCount) + (rage ? 1 : 0);
        final List<UUID> drops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Cow drop = EntityType.COW.create(level);
            if (drop == null) {
                continue;
            }
            double ox = target.getX() + (i - (count - 1) / 2.0D) * 1.3D;
            double oz = target.getZ() + ((i % 2 == 0) ? 0.9D : -0.9D);
            drop.moveTo(ox, target.getY() + 8.0D + i * 0.6D, oz, level.random.nextFloat() * 360.0F, 0.0F);
            drop.setNoAi(true);
            drop.setInvulnerable(true);
            drop.setSilent(true);
            drop.addTag("supercow_cow_rain");
            if (!level.addFreshEntity(drop)) {
                drop.remove();
                continue;
            }
            SkillCleanup.track(drop, ownerUuid, dur + 80, "cow_rain");
            drops.add(drop.getUUID());
        }
        if (drops.isEmpty()) {
            return false;
        }
        final float damage = (float) cfg.cowRainDamage;
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                for (Iterator<UUID> it = drops.iterator(); it.hasNext(); ) {
                    UUID id = it.next();
                    Entity entity = level.getEntity(id);
                    if (!(entity instanceof Cow)) {
                        it.remove();
                        continue;
                    }
                    Cow cow = (Cow) entity;
                    if (cow.removed || !cow.isAlive()) {
                        it.remove();
                        continue;
                    }
                    level.sendParticles(ParticleTypes.CLOUD, cow.getX(), cow.getY() + 0.6D, cow.getZ(), 3, 0.2D, 0.2D, 0.2D, 0.01D);
                    boolean landed = cow.getY() <= 0.0D || isSolidBelow(level, cow);
                    if (!landed) {
                        cow.moveTo(cow.getX(), cow.getY() - 0.8D, cow.getZ(), cow.yRot, cow.xRot);
                        continue;
                    }
                    for (LivingEntity victim : nearby(level, cow.getX(), cow.getY(), cow.getZ(), 2.0D, ownerUuid, pet)) {
                        damage(victim, damage, pet, ownerUuid);
                    }
                    level.sendParticles(ParticleTypes.POOF, cow.getX(), cow.getY() + 0.4D, cow.getZ(), 12, 0.4D, 0.3D, 0.4D, 0.05D);
                    SkillCleanup.untrack(cow);
                    cow.remove();
                    it.remove();
                }
                return !drops.isEmpty();
            }
        });
        return true;
    }

    /** 牛奶炸弹：先飞一小段，落点清除目标增益 + 缓慢，伤害很低。 */
    private static boolean milkBomb(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, SuperCowConfig cfg) {
        if (target == null) {
            return false;
        }
        final Vec3 from = pet.position();
        final Vec3 to = target.position();
        final float damage = (float) cfg.milkBombDamage;
        final int slowTicks = Math.max(20, cfg.milkBombSlownessTicks);
        final double radius = rage ? 4.5D : 3.5D;
        TASKS.add(new Task(level, ownerUuid, 14) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                double progress = Math.min(1.0D, age / 10.0D);
                double x = from.x + (to.x - from.x) * progress;
                double y = from.y + 1.0D + (to.y - from.y) * progress + Math.sin(progress * Math.PI) * 1.2D;
                double z = from.z + (to.z - from.z) * progress;
                level.sendParticles(ParticleTypes.CLOUD, x, y, z, 4, 0.1D, 0.1D, 0.1D, 0.01D);
                if (age < 10) {
                    return true;
                }
                level.sendParticles(ParticleTypes.POOF, to.x, to.y + 0.4D, to.z, 30, 1.6D, 0.6D, 1.6D, 0.02D);
                for (LivingEntity victim : nearby(level, to.x, to.y, to.z, radius, ownerUuid, pet)) {
                    removePositiveEffects(victim);
                    victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, slowTicks, 1));
                    damage(victim, damage, pet, ownerUuid);
                }
                if (pet.isAlive()) {
                    pet.heal(1.0F);
                }
                return false;
            }
        });
        return true;
    }

    /** 月球漫步：宠物自己获得跳跃/缓降/加速，一路弹跳。 */
    private static boolean moonWalk(ServerLevel level, Cow pet, UUID ownerUuid, SuperCowConfig cfg, int dur) {
        pet.addEffect(new MobEffectInstance(MobEffects.JUMP, dur, 2));
        pet.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, dur, 0));
        pet.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, dur, 0));
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                if (!pet.isAlive()) {
                    return false;
                }
                level.sendParticles(ParticleTypes.END_ROD, pet.getX(), pet.getY() + 0.3D, pet.getZ(), 1, 0.2D, 0.1D, 0.2D, 0.01D);
                if (pet.isOnGround() && level.random.nextDouble() < 0.35D) {
                    Vec3 v = pet.getDeltaMovement();
                    pet.setDeltaMovement(v.x, 0.55D, v.z);
                }
                return true;
            }
        });
        return true;
    }

    /** 哞叫波：两圈音波把周围推开。 */
    private static boolean mooWave(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, SuperCowConfig cfg, int dur) {
        final float damage = (float) cfg.mooWaveDamage;
        final double strength = cfg.mooWaveKnockback;
        final double radius = rage ? 5.0D : 4.0D;
        for (int wave = 0; wave < 2; wave++) {
            final int delay = wave * 10;
            final int waveIndex = wave;
            TASKS.add(new Task(level, ownerUuid, delay + 20) {
                @Override
                boolean tick(long now) {
                    if (now - startTick < delay) {
                        return true;
                    }
                    if (!pet.isAlive()) {
                        return false;
                    }
                    ring(level, pet.getX(), pet.getY() + 0.6D, pet.getZ(), radius);
                    if (waveIndex == 1) {
                        pet.playSound(net.minecraft.sounds.SoundEvents.COW_AMBIENT, 1.2F, 0.6F);
                    }
                    for (LivingEntity victim : nearby(level, pet.getX(), pet.getY(), pet.getZ(), radius, ownerUuid, pet)) {
                        damage(victim, damage, pet, ownerUuid);
                        Vec3 push = victim.position().subtract(pet.position()).multiply(1.0D, 0.0D, 1.0D);
                        if (push.lengthSqr() < 0.01D) {
                            push = new Vec3(1.0D, 0.0D, 0.0D);
                        }
                        push = push.normalize().scale(strength);
                        victim.push(push.x, 0.18D, push.z);
                    }
                    return false;
                }
            });
        }
        return true;
    }

    /** 干草旋风：干草粒子绕着目标转，把怪物慢慢吸进去。 */
    private static boolean hayTornado(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, SuperCowConfig cfg, int dur) {
        if (target == null) {
            return false;
        }
        final Vec3 center = target.position();
        final double radius = rage ? 5.5D : 4.5D;
        final float damage = (float) cfg.hayTornadoDamage;
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (!pet.isAlive()) {
                    return false;
                }
                for (int i = 0; i < 3; i++) {
                    double angle = age * 0.4D + i * (Math.PI * 2.0D / 3.0D);
                    double r = 1.4D + (i * 0.5D);
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                        center.x + Math.cos(angle) * r, center.y + 0.3D + (age % 20) * 0.06D, center.z + Math.sin(angle) * r,
                        1, 0.05D, 0.05D, 0.05D, 0.0D);
                }
                if (age % 8 == 0) {
                    for (LivingEntity victim : nearby(level, center.x, center.y, center.z, radius, ownerUuid, pet, target)) {
                        Vec3 pull = center.subtract(victim.position()).multiply(1.0D, 0.0D, 1.0D);
                        if (pull.lengthSqr() > 1.0D) {
                            pull = pull.normalize().scale(0.16D);
                            victim.push(pull.x, 0.0D, pull.z);
                        }
                    }
                }
                if (age % 20 == 0) {
                    for (LivingEntity victim : nearby(level, center.x, center.y, center.z, 2.2D, ownerUuid, pet, target)) {
                        damage(victim, damage, pet, ownerUuid);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 蝴蝶群：几只蝙蝠绕着目标乱飞，隔一会儿舔一下。 */
    private static boolean butterflySwarm(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, SuperCowConfig cfg, int dur) {
        if (target == null) {
            return false;
        }
        int count = Math.max(1, cfg.butterflyCount) + (rage ? 1 : 0);
        final List<UUID> bats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            net.minecraft.world.entity.ambient.Bat bat = EntityType.BAT.create(level);
            if (bat == null) {
                continue;
            }
            bat.moveTo(target.getX(), target.getY() + 1.5D, target.getZ(), 0.0F, 0.0F);
            bat.setNoAi(true);
            bat.setInvulnerable(true);
            bat.setSilent(true);
            bat.addTag("supercow_butterfly");
            if (!level.addFreshEntity(bat)) {
                bat.remove();
                continue;
            }
            SkillCleanup.track(bat, ownerUuid, dur + 40, "butterfly");
            bats.add(bat.getUUID());
        }
        if (bats.isEmpty()) {
            return false;
        }
        final float damage = (float) cfg.butterflyDamage;
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                if (!bats.isEmpty()) {
                    for (int i = 0; i < bats.size(); i++) {
                        Entity entity = level.getEntity(bats.get(i));
                        if (!(entity instanceof Mob)) {
                            continue;
                        }
                        Mob bat = (Mob) entity;
                        if (bat.removed || !bat.isAlive()) {
                            continue;
                        }
                        double angle = (now * 0.22D) + i * (Math.PI * 2.0D / bats.size());
                        bat.moveTo(target.getX() + Math.cos(angle) * 1.7D, target.getY() + 1.4D + Math.sin(angle * 1.7D) * 0.4D,
                            target.getZ() + Math.sin(angle) * 1.7D, bat.yRot, bat.xRot);
                    }
                }
                if (now % 20 == 0 && target.isAlive()) {
                    level.sendParticles(ParticleTypes.HEART, target.getX(), target.getY() + 1.2D, target.getZ(), 3, 0.3D, 0.3D, 0.3D, 0.0D);
                    damage(target, damage, pet, ownerUuid);
                }
                return true;
            }
        });
        return true;
    }

    /** 零重力：附近怪物（和宠物自己）一起飘起来。 */
    private static boolean zeroGravity(ServerLevel level, Cow pet, UUID ownerUuid, SuperCowConfig cfg, int dur) {
        final double lift = cfg.zeroGravityLift;
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                if (!pet.isAlive()) {
                    return false;
                }
                if (now % 2 == 0) {
                    for (LivingEntity victim : nearby(level, pet.getX(), pet.getY(), pet.getZ(), 6.0D, ownerUuid, pet)) {
                        Vec3 v = victim.getDeltaMovement();
                        victim.setDeltaMovement(v.x, Math.min(lift, v.y + lift * 0.5D), v.z);
                        level.sendParticles(ParticleTypes.END_ROD, victim.getX(), victim.getY() + 0.2D, victim.getZ(), 1, 0.2D, 0.2D, 0.2D, 0.0D);
                    }
                    Vec3 pv = pet.getDeltaMovement();
                    pet.setDeltaMovement(pv.x, Math.min(lift * 0.8D, pv.y + lift * 0.4D), pv.z);
                }
                return true;
            }
        });
        return true;
    }

    /** 镜像牛：刷一只无敌的假牛替你吸引仇恨，时间到就散。 */
    private static boolean mirrorCow(ServerLevel level, Cow pet, UUID ownerUuid, SuperCowConfig cfg, int dur) {
        Cow mirror = EntityType.COW.create(level);
        if (mirror == null) {
            return false;
        }
        mirror.moveTo(pet.getX() + 1.0D, pet.getY(), pet.getZ() + 1.0D, pet.yRot, 0.0F);
        mirror.setCustomName(pet.getCustomName());
        mirror.setCustomNameVisible(true);
        mirror.setNoAi(true);
        mirror.setInvulnerable(true);
        mirror.setSilent(true);
        mirror.addTag("supercow_mirror");
        if (!level.addFreshEntity(mirror)) {
            mirror.remove();
            return false;
        }
        SkillCleanup.track(mirror, ownerUuid, dur + 20, "mirror_cow");
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                if (!mirror.isAlive() || mirror.removed) {
                    return false;
                }
                if (now % 10 == 0) {
                    AABB box = mirror.getBoundingBox().inflate(8.0D);
                    for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
                        if (mob == mirror || mob == pet || !mob.isAlive()) {
                            continue;
                        }
                        if (TargetValidator.isFriendlyEntity(mob, ownerUuid)) {
                            continue;
                        }
                        mob.setTarget(mirror);
                    }
                    level.sendParticles(ParticleTypes.PORTAL, mirror.getX(), mirror.getY() + 1.0D, mirror.getZ(), 8, 0.4D, 0.6D, 0.4D, 0.02D);
                }
                return true;
            }
        });
        return true;
    }

    /** 换位：宠物和目标瞬间交换坐标。 */
    private static boolean teleportSwap(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, SuperCowConfig cfg) {
        if (target == null || target == pet || target.level != pet.level) {
            return false;
        }
        if (pet.distanceToSqr(target) > 16.0D * 16.0D) {
            return false;
        }
        Vec3 petPos = pet.position();
        Vec3 targetPos = target.position();
        level.sendParticles(ParticleTypes.PORTAL, petPos.x, petPos.y + 0.9D, petPos.z, 30, 0.4D, 0.6D, 0.4D, 0.05D);
        level.sendParticles(ParticleTypes.PORTAL, targetPos.x, targetPos.y + 0.9D, targetPos.z, 30, 0.4D, 0.6D, 0.4D, 0.05D);
        pet.teleportTo(targetPos.x, targetPos.y, targetPos.z);
        target.teleportTo(petPos.x, petPos.y, petPos.z);
        damage(target, (float) cfg.teleportSwapDamage, pet, ownerUuid);
        return true;
    }

    /** 彩纸爆炸：一堆烟花粒子，伤害低，很吵。 */
    private static boolean confetti(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, SuperCowConfig cfg) {
        Vec3 at = target != null ? target.position() : pet.position();
        double radius = rage ? 4.5D : 3.5D;
        level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y + 1.0D, at.z, 60, 1.2D, 1.0D, 1.2D, 0.12D);
        level.sendParticles(ParticleTypes.POOF, at.x, at.y + 0.6D, at.z, 15, 1.0D, 0.5D, 1.0D, 0.05D);
        pet.playSound(net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_BLAST, 1.4F, 1.1F);
        for (LivingEntity victim : nearby(level, at.x, at.y, at.z, radius, ownerUuid, pet)) {
            damage(victim, (float) cfg.confettiDamage, pet, ownerUuid);
            victim.push(0.0D, 0.18D, 0.0D);
        }
        return true;
    }

    // ------------------------------------------------------------------ ticking / cleanup

    public static void tickAll() {
        if (TASKS.isEmpty()) {
            return;
        }
        long now = SuperCowMod.getServerTicksStatic();
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task task = it.next();
            boolean alive;
            try {
                alive = now < task.endTick && task.tick(now);
            } catch (Exception e) {
                alive = false;
            }
            if (!alive) {
                it.remove();
            }
        }
    }

    public static void cleanupForOwner(UUID ownerUuid) {
        TASKS.removeIf(task -> task.ownerUuid.equals(ownerUuid));
        // FIXED BUG: spawned helpers (cow rain drops, bats, vortex cores, ...) were tracked by
        // SkillCleanup but never despawned on pet death / owner logout, so they stayed forever.
        SkillCleanup.cleanupForOwner(ownerUuid);
    }

    public static void cleanupAll() {
        TASKS.clear();
    }

    public static int activeTasks() {
        return TASKS.size();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isSolidBelow(ServerLevel level, Entity entity) {
        return level.getBlockState(entity.blockPosition().below()).getMaterial().isSolid();
    }

    private static void ring(ServerLevel level, double x, double y, double z, double radius) {
        for (int i = 0; i < 36; i++) {
            double angle = i * (Math.PI * 2.0D / 36.0D);
            level.sendParticles(ParticleTypes.CLOUD, x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private static void removePositiveEffects(LivingEntity entity) {
        Collection<MobEffectInstance> effects = new ArrayList<>(entity.getActiveEffects());
        for (MobEffectInstance instance : effects) {
            MobEffect effect = instance.getEffect();
            if (effect.isBeneficial()) {
                entity.removeEffect(effect);
            }
        }
    }

    private static List<LivingEntity> nearby(ServerLevel level, double x, double y, double z, double radius, UUID ownerUuid, LivingEntity pet) {
        return nearby(level, x, y, z, radius, ownerUuid, pet, null);
    }

    /**
     * FIXED BUG: the skill's own target used to be passed as "explicitTarget" only when it was the
     * pet itself, so a non-monster target (e.g. a mob from another mod) was filtered out of its own
     * skill's area effect. Now the caller passes the real target and TargetValidator grants it the
     * explicit-target bypass.
     */
    private static List<LivingEntity> nearby(ServerLevel level, double x, double y, double z, double radius, UUID ownerUuid, LivingEntity pet, LivingEntity explicitTarget) {
        AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity == pet || entity instanceof Player) {
                continue;
            }
            if (!TargetValidator.isValidAoETarget(entity, ownerUuid, explicitTarget)) {
                continue;
            }
            out.add(entity);
        }
        return out;
    }

    private static void damage(LivingEntity victim, float amount, LivingEntity pet, UUID ownerUuid) {
        if (victim == null || !victim.isAlive() || amount <= 0.0F) {
            return;
        }
        SuperCowMod.recordDamageOwner(victim.getUUID(), ownerUuid);
        victim.hurt(net.minecraft.world.damagesource.DamageSource.mobAttack(pet), amount);
    }

    private abstract static class Task {
        final ServerLevel level;
        final UUID ownerUuid;
        final long startTick;
        final long endTick;

        Task(ServerLevel level, UUID ownerUuid, int duration) {
            this.level = level;
            this.ownerUuid = ownerUuid;
            this.startTick = SuperCowMod.getServerTicksStatic();
            this.endTick = this.startTick + Math.max(1L, (long) duration);
        }

        abstract boolean tick(long now);
    }
}
