package net.dabbit.supercow.combat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.combat.CustomProjectiles.ArrowEffectType;
import net.dabbit.supercow.combat.CustomProjectiles.SuperCowArrow;
import net.dabbit.supercow.combat.CustomProjectiles.SuperCowEgg;
import net.dabbit.supercow.combat.CustomProjectiles.SuperCowFireball;
import net.dabbit.supercow.combat.CustomProjectiles.SuperCowPotion;
import net.dabbit.supercow.config.SuperCowConfig;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Manages all special attacks and active tick-based combat tasks for SuperCow.
 * Fixes all original bugs (exploding_cow dispatch, chicken bombardment egg target filter,
 * music attack commented-out code + inverted target check + zero-vector NaN knockback,
 * exploding cow 5-tick lifetime, and terrain block destruction).
 */
public final class SpecialAttackManagers {
    private static final Random RANDOM = new Random();
    private static final ArrowEffectType[] SPECIAL_ARROW_EFFECTS = new ArrowEffectType[]{
        ArrowEffectType.FROST,
        ArrowEffectType.LIGHTNING,
        ArrowEffectType.EXPLOSION,
        ArrowEffectType.VORTEX,
        ArrowEffectType.RAIN,
        ArrowEffectType.BOUNCE
    };
    private static final SoundEvent[] MUSIC_SOUNDS = new SoundEvent[]{
        SoundEvents.NOTE_BLOCK_HARP,
        SoundEvents.NOTE_BLOCK_BELL,
        SoundEvents.NOTE_BLOCK_CHIME,
        SoundEvents.NOTE_BLOCK_FLUTE,
        SoundEvents.NOTE_BLOCK_GUITAR,
        SoundEvents.NOTE_BLOCK_PLING,
        SoundEvents.NOTE_BLOCK_XYLOPHONE
    };

    private static final List<BombardmentTask> ACTIVE_BOMBARDMENTS = new ArrayList<>();
    private static final List<MusicAttackTask> ACTIVE_MUSIC_ATTACKS = new ArrayList<>();
    private static final List<ExplodingCowTask> ACTIVE_EXPLODING_COWS = new ArrayList<>();
    private static final List<FireworkTask> ACTIVE_FIREWORKS = new ArrayList<>();

    private SpecialAttackManagers() {
    }

    public static void tickAll() {
        Iterator<BombardmentTask> bombIt = ACTIVE_BOMBARDMENTS.iterator();
        while (bombIt.hasNext()) {
            BombardmentTask task = bombIt.next();
            if (!task.tick()) {
                task.cleanup();
                bombIt.remove();
            }
        }

        Iterator<MusicAttackTask> musicIt = ACTIVE_MUSIC_ATTACKS.iterator();
        while (musicIt.hasNext()) {
            MusicAttackTask task = musicIt.next();
            if (!task.tick()) {
                musicIt.remove();
            }
        }

        Iterator<ExplodingCowTask> cowIt = ACTIVE_EXPLODING_COWS.iterator();
        while (cowIt.hasNext()) {
            ExplodingCowTask task = cowIt.next();
            if (!task.tick()) {
                task.cleanup();
                cowIt.remove();
            }
        }

        Iterator<FireworkTask> fwIt = ACTIVE_FIREWORKS.iterator();
        while (fwIt.hasNext()) {
            FireworkTask task = fwIt.next();
            if (!task.tick()) {
                fwIt.remove();
            }
        }
    }

    public static void cleanupAll() {
        for (BombardmentTask task : ACTIVE_BOMBARDMENTS) {
            task.cleanup();
        }
        ACTIVE_BOMBARDMENTS.clear();
        ACTIVE_MUSIC_ATTACKS.clear();
        for (ExplodingCowTask task : ACTIVE_EXPLODING_COWS) {
            task.cleanup();
        }
        ACTIVE_EXPLODING_COWS.clear();
        ACTIVE_FIREWORKS.clear();
    }

    public static void cleanupForOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return;
        }
        Iterator<BombardmentTask> bombIt = ACTIVE_BOMBARDMENTS.iterator();
        while (bombIt.hasNext()) {
            BombardmentTask task = bombIt.next();
            if (ownerUuid.equals(task.ownerUuid)) {
                task.cleanup();
                bombIt.remove();
            }
        }
        ACTIVE_MUSIC_ATTACKS.removeIf(task -> ownerUuid.equals(task.ownerUuid));
        Iterator<ExplodingCowTask> cowIt = ACTIVE_EXPLODING_COWS.iterator();
        while (cowIt.hasNext()) {
            ExplodingCowTask task = cowIt.next();
            if (ownerUuid.equals(task.ownerUuid)) {
                task.cleanup();
                cowIt.remove();
            }
        }
        ACTIVE_FIREWORKS.removeIf(task -> ownerUuid.equals(task.ownerUuid));
    }

    // =========================================================================
    // 1. Arrow Attack & 6 Conservative Arrow Effects
    // =========================================================================
    public static void shootArrows(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, LivingEntity target, boolean rageMode) {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        int count = rageMode ? cfg.rageArrowCount : cfg.normalArrowCount;
        double damage = rageMode ? cfg.rageArrowDamage : cfg.normalArrowDamage;

        for (int i = 0; i < count; i++) {
            ArrowEffectType effect = ArrowEffectType.NONE;
            if (RANDOM.nextDouble() < cfg.arrowEffectChance) {
                effect = SPECIAL_ARROW_EFFECTS[RANDOM.nextInt(SPECIAL_ARROW_EFFECTS.length)];
            }
            SuperCowArrow arrow = new SuperCowArrow(
                level,
                shooterCow,
                ownerUuid,
                damage,
                rageMode,
                false,
                effect,
                effect == ArrowEffectType.BOUNCE ? cfg.bounceMaxCount : 0
            );
            Vec3 from = new Vec3(shooterCow.getX(), shooterCow.getEyeY() - 0.1D, shooterCow.getZ());
            Vec3 to = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ());
            Vec3 dir = to.subtract(from);
            double horizDist = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
            arrow.shoot(dir.x, dir.y + horizDist * 0.18D, dir.z, (float) cfg.arrowSpeed, (float) (cfg.arrowSpread + i * 2.0D));
            level.addFreshEntity(arrow);
            SkillCleanup.track(arrow, ownerUuid, 200, "arrow");
        }
        // Original plugin quirk kept as a skill: every arrow volley healed the cow a little
        // (ArrowConfig.HEAL_AMOUNT was 2.5; the port keeps a smaller 1.0 so it stays balanced).
        if (cfg.legacyQuirksEnabled && cfg.arrowHealAmount > 0.0D && shooterCow != null && shooterCow.isAlive()) {
            float maxHealth = shooterCow.getMaxHealth();
            if (shooterCow.getHealth() < maxHealth) {
                shooterCow.setHealth((float) Math.min(maxHealth, shooterCow.getHealth() + cfg.arrowHealAmount));
                level.sendParticles(ParticleTypes.HEART, shooterCow.getX(), shooterCow.getY() + 1.3D, shooterCow.getZ(), 2, 0.3D, 0.3D, 0.3D, 0.02D);
            }
        }
        level.playSound(null, shooterCow.getX(), shooterCow.getY(), shooterCow.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 0.8F, 1.0F);
    }

    public static void applyArrowEffect(
        ServerLevel level,
        LivingEntity shooterCow,
        UUID ownerUuid,
        LivingEntity primaryTarget,
        Vec3 hitPos,
        ArrowEffectType effectType,
        boolean rageMode,
        double currentDamage,
        int remainingBounces,
        Set<UUID> hitEntities
    ) {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        DamageSource source = shooterCow != null ? DamageSource.mobAttack(shooterCow) : DamageSource.GENERIC;

        switch (effectType) {
            case FROST: {
                primaryTarget.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, cfg.frostDurationTicks, rageMode ? 1 : 0));
                primaryTarget.hurt(source, (float) cfg.frostBonusDamage);
                level.sendParticles(ParticleTypes.ITEM_SNOWBALL, hitPos.x, hitPos.y + 0.5D, hitPos.z, 8, 0.3D, 0.3D, 0.3D, 0.05D);
                break;
            }
            case LIGHTNING: {
                float boltDmg = (float) (rageMode ? cfg.rageLightningDamage : cfg.lightningDamage);
                primaryTarget.hurt(DamageSource.LIGHTNING_BOLT, boltDmg);
                // Visual-only lightning bolt so it never starts fires or turns pigs/villagers into witches
                LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
                if (bolt != null) {
                    bolt.moveTo(hitPos.x, hitPos.y, hitPos.z);
                    bolt.setVisualOnly(true);
                    level.addFreshEntity(bolt);
                }
                break;
            }
            case EXPLOSION: {
                applyArrowGroundEffect(level, shooterCow, ownerUuid, hitPos, ArrowEffectType.EXPLOSION, rageMode);
                break;
            }
            case VORTEX: {
                applyArrowGroundEffect(level, shooterCow, ownerUuid, hitPos, ArrowEffectType.VORTEX, rageMode);
                break;
            }
            case RAIN: {
                applyArrowGroundEffect(level, shooterCow, ownerUuid, hitPos, ArrowEffectType.RAIN, rageMode);
                break;
            }
            case BOUNCE: {
                if (remainingBounces > 0 && shooterCow != null) {
                    double range = cfg.bounceRange;
                    AABB box = primaryTarget.getBoundingBox().inflate(range);
                    List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box);
                    LivingEntity nextTarget = null;
                    double bestDist = Double.MAX_VALUE;
                    for (LivingEntity candidate : candidates) {
                        if (hitEntities.contains(candidate.getUUID())) {
                            continue;
                        }
                        if (!TargetValidator.isValidCombatTarget(candidate, ownerUuid, shooterCow, false)) {
                            continue;
                        }
                        double d = candidate.distanceToSqr(primaryTarget);
                        if (d < bestDist) {
                            bestDist = d;
                            nextTarget = candidate;
                        }
                    }
                    if (nextTarget != null) {
                        double nextDmg = Math.max(1.0D, currentDamage * cfg.bounceDamageDecay);
                        SuperCowArrow bounceArrow = new SuperCowArrow(
                            level,
                            shooterCow,
                            ownerUuid,
                            nextDmg,
                            rageMode,
                            false,
                            ArrowEffectType.BOUNCE,
                            remainingBounces - 1
                        );
                        bounceArrow.setPos(primaryTarget.getX(), primaryTarget.getEyeY(), primaryTarget.getZ());
                        Vec3 dir = nextTarget.position().add(0.0D, nextTarget.getBbHeight() * 0.5D, 0.0D)
                            .subtract(bounceArrow.position());
                        bounceArrow.shoot(dir.x, dir.y + 0.1D, dir.z, 1.4F, 1.0F);
                        level.addFreshEntity(bounceArrow);
                        level.sendParticles(ParticleTypes.CRIT, primaryTarget.getX(), primaryTarget.getEyeY(), primaryTarget.getZ(), 5, 0.2D, 0.2D, 0.2D, 0.05D);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    public static void applyArrowGroundEffect(
        ServerLevel level,
        LivingEntity shooterCow,
        UUID ownerUuid,
        Vec3 hitPos,
        ArrowEffectType effectType,
        boolean rageMode
    ) {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        DamageSource source = shooterCow != null ? DamageSource.mobAttack(shooterCow) : DamageSource.GENERIC;

        if (effectType == ArrowEffectType.EXPLOSION) {
            double r = cfg.explosionArrowRadius;
            AABB box = new AABB(hitPos.x - r, hitPos.y - r, hitPos.z - r, hitPos.x + r, hitPos.y + r, hitPos.z + r);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (!TargetValidator.isValidAoETarget(entity, ownerUuid, null)) {
                    continue;
                }
                double dist = entity.position().distanceTo(hitPos);
                if (dist <= r) {
                    float dmg = (float) (cfg.explosionArrowDamage * (1.0D - dist / (r + 0.5D)));
                    entity.hurt(source, Math.max(0.5F, dmg));
                }
            }
            level.sendParticles(ParticleTypes.EXPLOSION, hitPos.x, hitPos.y + 0.2D, hitPos.z, 2, 0.2D, 0.2D, 0.2D, 0.0D);
            level.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 0.6F, 1.2F);
        } else if (effectType == ArrowEffectType.VORTEX) {
            double r = cfg.vortexRadius;
            AABB box = new AABB(hitPos.x - r, hitPos.y - r, hitPos.z - r, hitPos.x + r, hitPos.y + r, hitPos.z + r);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (!TargetValidator.isValidAoETarget(entity, ownerUuid, null)) {
                    continue;
                }
                Vec3 pull = hitPos.subtract(entity.position());
                if (pull.lengthSqr() > 1.0E-4D) {
                    Vec3 v = pull.normalize().scale(cfg.vortexPullStrength);
                    entity.setDeltaMovement(entity.getDeltaMovement().add(v.x, 0.08D, v.z));
                }
                entity.hurt(source, (float) cfg.vortexDamage);
            }
            level.sendParticles(ParticleTypes.PORTAL, hitPos.x, hitPos.y + 0.5D, hitPos.z, 12, 0.4D, 0.4D, 0.4D, 0.1D);
        } else if (effectType == ArrowEffectType.RAIN && shooterCow != null) {
            int count = cfg.rainArrowCount;
            for (int i = 0; i < count; i++) {
                double ox = (RANDOM.nextDouble() - 0.5D) * 2.0D * cfg.rainRadius;
                double oz = (RANDOM.nextDouble() - 0.5D) * 2.0D * cfg.rainRadius;
                SuperCowArrow rainArrow = new SuperCowArrow(
                    level,
                    shooterCow,
                    ownerUuid,
                    cfg.rainArrowDamage,
                    rageMode,
                    false,
                    ArrowEffectType.NONE,
                    0
                );
                rainArrow.setPos(hitPos.x + ox, hitPos.y + 5.0D, hitPos.z + oz);
                rainArrow.shoot(0.0D, -1.0D, 0.0D, 1.1F, 2.0F);
                level.addFreshEntity(rainArrow);
            }
            level.sendParticles(ParticleTypes.CLOUD, hitPos.x, hitPos.y + 5.0D, hitPos.z, 8, 1.0D, 0.2D, 1.0D, 0.02D);
        }
    }

    // =========================================================================
    // 2. Fireball & Splash Potion Attacks
    // =========================================================================
    public static void shootFireball(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, LivingEntity target, boolean rageMode) {
        Vec3 from = new Vec3(shooterCow.getX(), shooterCow.getEyeY(), shooterCow.getZ());
        Vec3 to = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ());
        Vec3 dir = to.subtract(from);
        if (dir.lengthSqr() < 1.0E-4D) {
            dir = new Vec3(0.0D, 0.0D, 1.0D);
        } else {
            dir = dir.normalize();
        }
        SuperCowFireball fireball = new SuperCowFireball(level, shooterCow, ownerUuid, dir.x, dir.y, dir.z, rageMode);
        level.addFreshEntity(fireball);
        level.playSound(null, shooterCow.getX(), shooterCow.getY(), shooterCow.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, 0.7F, 1.0F);
    }

    public static void shootPotion(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, LivingEntity target, boolean rageMode) {
        SuperCowPotion potion = new SuperCowPotion(level, shooterCow, ownerUuid, target, rageMode);
        ItemStack stack = PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), rageMode ? Potions.POISON : Potions.SLOWNESS);
        potion.setItem(stack);
        Vec3 from = new Vec3(shooterCow.getX(), shooterCow.getEyeY(), shooterCow.getZ());
        Vec3 to = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ());
        Vec3 dir = to.subtract(from);
        double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        potion.shoot(dir.x, dir.y + horiz * 0.2D, dir.z, 0.85F, 2.0F);
        level.addFreshEntity(potion);
        level.playSound(null, shooterCow.getX(), shooterCow.getY(), shooterCow.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.NEUTRAL, 0.7F, 1.0F);
    }

    // =========================================================================
    // 3. Firework Attack
    // =========================================================================
    public static void launchFireworks(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, LivingEntity target, boolean rageMode) {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        int count = rageMode ? cfg.rageFireworkCount : cfg.normalFireworkCount;
        for (int i = 0; i < count; i++) {
            ACTIVE_FIREWORKS.add(new FireworkTask(level, shooterCow, ownerUuid, target, rageMode, i * 5));
        }
        level.playSound(null, shooterCow.getX(), shooterCow.getY(), shooterCow.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.NEUTRAL, 0.8F, 1.0F);
    }

    private static class FireworkTask {
        final ServerLevel level;
        final LivingEntity shooterCow;
        final UUID ownerUuid;
        final LivingEntity target;
        final boolean rageMode;
        int delayTicks;
        int ticksRun = 0;
        Vec3 currentPos;

        FireworkTask(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, LivingEntity target, boolean rageMode, int delayTicks) {
            this.level = level;
            this.shooterCow = shooterCow;
            this.ownerUuid = ownerUuid;
            this.target = target;
            this.rageMode = rageMode;
            this.delayTicks = delayTicks;
            this.currentPos = shooterCow.position().add(0.0D, 1.0D, 0.0D);
        }

        boolean tick() {
            if (delayTicks > 0) {
                delayTicks--;
                return true;
            }
            ticksRun++;
            if (target == null || !target.isAlive() || ticksRun > 30) {
                explode();
                return false;
            }
            Vec3 targetPos = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
            Vec3 delta = targetPos.subtract(currentPos);
            double dist = delta.length();
            if (dist <= 1.2D) {
                currentPos = targetPos;
                explode();
                return false;
            }
            Vec3 step = delta.normalize().scale(Math.min(0.8D, dist));
            currentPos = currentPos.add(step);
            level.sendParticles(ParticleTypes.FIREWORK, currentPos.x, currentPos.y, currentPos.z, 3, 0.1D, 0.1D, 0.1D, 0.02D);
            return true;
        }

        void explode() {
            SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
            double r = rageMode ? cfg.rageFireworkRadius : cfg.fireworkRadius;
            float baseDmg = (float) (rageMode ? cfg.rageFireworkDamage : cfg.fireworkDamage);
            DamageSource src = shooterCow != null ? DamageSource.mobAttack(shooterCow) : DamageSource.GENERIC;

            AABB box = new AABB(currentPos.x - r, currentPos.y - r, currentPos.z - r, currentPos.x + r, currentPos.y + r, currentPos.z + r);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (!TargetValidator.isValidAoETarget(entity, ownerUuid, target)) {
                    continue;
                }
                double dist = entity.position().distanceTo(currentPos);
                if (dist <= r) {
                    float scaled = (float) (baseDmg * Math.max(0.3D, 1.0D - dist / (r + 0.5D)));
                    entity.hurt(src, scaled);
                }
            }
            level.sendParticles(ParticleTypes.FIREWORK, currentPos.x, currentPos.y, currentPos.z, 16, 0.5D, 0.5D, 0.5D, 0.15D);
            level.sendParticles(
                new DustParticleOptions(1.0F, rageMode ? 0.2F : 0.8F, 0.2F, 1.0F),
                currentPos.x, currentPos.y, currentPos.z,
                10, 0.4D, 0.4D, 0.4D, 0.05D
            );
            level.playSound(null, currentPos.x, currentPos.y, currentPos.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.NEUTRAL, 0.8F, 1.0F);
        }
    }

    // =========================================================================
    // 4. Chicken Bombardment Attack (Fixed egg impact handler & target filter)
    // =========================================================================
    public static void startChickenBombardment(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, LivingEntity target, boolean rageMode) {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        int count = rageMode ? cfg.rageChickenCount : cfg.normalChickenCount;
        List<Chicken> chickens = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Chicken chicken = EntityType.CHICKEN.create(level);
            if (chicken == null) {
                continue;
            }
            double angle = (2.0D * Math.PI * i) / Math.max(1, count);
            double x = target.getX() + Math.cos(angle) * 2.0D;
            double y = target.getY() + cfg.chickenHeightOffset;
            double z = target.getZ() + Math.sin(angle) * 2.0D;
            chicken.moveTo(x, y, z, 0.0F, 0.0F);
            chicken.setCustomName(new TextComponent(rageMode ? "§c§l狂暴轰炸鸡" : "§e轰炸鸡"));
            chicken.setCustomNameVisible(true);
            chicken.setNoGravity(true);
            chicken.setInvulnerable(true);
            chicken.addTag("supercow_bomber_chicken");
            if (ownerUuid != null) {
                chicken.addTag("supercow_owner:" + ownerUuid.toString());
            }
            level.addFreshEntity(chicken);
            SkillCleanup.track(chicken, ownerUuid, cfg.bombardmentDurationTicks + 60, "bomber_chicken");
            chickens.add(chicken);
        }

        if (!chickens.isEmpty()) {
            ACTIVE_BOMBARDMENTS.add(new BombardmentTask(level, shooterCow, ownerUuid, target, chickens, rageMode, cfg.bombardmentDurationTicks, cfg.eggDropIntervalTicks, cfg.chickenHeightOffset));
            level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.CHICKEN_AMBIENT, SoundSource.NEUTRAL, 0.8F, 1.2F);
        }
    }

    private static class BombardmentTask {
        final ServerLevel level;
        final LivingEntity shooterCow;
        final UUID ownerUuid;
        final LivingEntity target;
        final List<Chicken> chickens;
        final boolean rageMode;
        final int maxTicks;
        final int eggInterval;
        final double heightOffset;
        int ticks = 0;

        BombardmentTask(
            ServerLevel level,
            LivingEntity shooterCow,
            UUID ownerUuid,
            LivingEntity target,
            List<Chicken> chickens,
            boolean rageMode,
            int maxTicks,
            int eggInterval,
            double heightOffset
        ) {
            this.level = level;
            this.shooterCow = shooterCow;
            this.ownerUuid = ownerUuid;
            this.target = target;
            this.chickens = chickens;
            this.rageMode = rageMode;
            this.maxTicks = maxTicks;
            this.eggInterval = Math.max(10, eggInterval);
            this.heightOffset = heightOffset;
        }

        boolean tick() {
            ticks++;
            if (ticks > maxTicks || target == null || !target.isAlive()) {
                return false;
            }
            int size = chickens.size();
            for (int i = 0; i < size; i++) {
                Chicken chicken = chickens.get(i);
                if (chicken == null || !chicken.isAlive()) {
                    continue;
                }
                double angle = (ticks * 0.15D) + (2.0D * Math.PI * i) / size;
                double nx = target.getX() + Math.cos(angle) * 2.0D;
                double ny = target.getY() + heightOffset;
                double nz = target.getZ() + Math.sin(angle) * 2.0D;
                chicken.moveTo(nx, ny, nz, chicken.yRot + 10.0F, 0.0F);

                if (ticks % eggInterval == 0) {
                    SuperCowEgg egg = new SuperCowEgg(level, nx, ny - 0.3D, nz, shooterCow, ownerUuid, target, rageMode);
                    Vec3 dir = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D).subtract(nx, ny - 0.3D, nz);
                    egg.shoot(dir.x, dir.y, dir.z, 0.8F, 1.5F);
                    level.addFreshEntity(egg);
                    SkillCleanup.track(egg, ownerUuid, 200, "egg");
                    level.playSound(null, nx, ny, nz, SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 0.6F, 1.0F);
                }
            }
            return true;
        }

        void cleanup() {
            for (Chicken chicken : chickens) {
                if (chicken != null && !chicken.removed) {
                    level.sendParticles(ParticleTypes.CLOUD, chicken.getX(), chicken.getY(), chicken.getZ(), 4, 0.2D, 0.2D, 0.2D, 0.02D);
                    chicken.remove();
                }
                SkillCleanup.untrack(chicken);
            }
            chickens.clear();
        }
    }

    // =========================================================================
    // 5. Music Attack (Fixed commented-out methods, inverted target check & NaN knockback)
    // =========================================================================
    public static void startMusicAttack(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, LivingEntity target, boolean rageMode) {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        ACTIVE_MUSIC_ATTACKS.add(new MusicAttackTask(level, shooterCow, ownerUuid, target, rageMode, cfg.musicDurationTicks, cfg.musicTickInterval));
    }

    private static class MusicAttackTask {
        final ServerLevel level;
        final LivingEntity shooterCow;
        final UUID ownerUuid;
        final LivingEntity target;
        final boolean rageMode;
        final int maxTicks;
        final int interval;
        int ticks = 0;

        MusicAttackTask(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, LivingEntity target, boolean rageMode, int maxTicks, int interval) {
            this.level = level;
            this.shooterCow = shooterCow;
            this.ownerUuid = ownerUuid;
            this.target = target;
            this.rageMode = rageMode;
            this.maxTicks = maxTicks;
            this.interval = Math.max(5, interval);
        }

        boolean tick() {
            ticks++;
            if (ticks > maxTicks || shooterCow == null || !shooterCow.isAlive()) {
                return false;
            }
            if (ticks % interval == 0) {
                SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
                double radius = cfg.musicRadius;
                Vec3 center = shooterCow.position().add(0.0D, 0.8D, 0.0D);

                // Expanding note ring particles (re-enabled & conservative count)
                double ringR = Math.min(radius, 1.0D + (ticks / (double) maxTicks) * (radius - 1.0D));
                int points = 8;
                for (int i = 0; i < points; i++) {
                    double angle = (2.0D * Math.PI * i) / points;
                    double px = center.x + Math.cos(angle) * ringR;
                    double pz = center.z + Math.sin(angle) * ringR;
                    level.sendParticles(ParticleTypes.NOTE, px, center.y, pz, 1, 0.0D, 0.0D, 0.0D, (i % 6) / 6.0D);
                }

                SoundEvent noteSound = MUSIC_SOUNDS[RANDOM.nextInt(MUSIC_SOUNDS.length)];
                level.playSound(null, center.x, center.y, center.z, noteSound, SoundSource.NEUTRAL, 0.7F, 0.8F + RANDOM.nextFloat() * 0.6F);

                float dmg = (float) (rageMode ? cfg.rageMusicDamage : cfg.musicDamage);
                DamageSource src = DamageSource.mobAttack(shooterCow);

                AABB box = shooterCow.getBoundingBox().inflate(radius);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
                    // FIXED BUG 1: Original had if (entity != target) applyMusicEffects(entity),
                    // which attacked the owner and pet cow while sparing the target!
                    if (!TargetValidator.isValidAoETarget(entity, ownerUuid, target)) {
                        continue;
                    }
                    double dist = entity.distanceTo(shooterCow);
                    if (dist > radius) {
                        continue;
                    }
                    entity.hurt(src, dmg);

                    // Quirk kept as a skill: the original applied the debuffs to everyone EXCEPT the
                    // primary target, so the crowd around the pet gets slowed/weakened while the main
                    // target walks away clean. Damage is still dealt normally to all valid targets.
                    boolean isPrimaryTarget = target != null && entity.getUUID().equals(target.getUUID());
                    boolean invertDebuffs = cfg.legacyQuirksEnabled && cfg.musicInvertedTargeting;
                    if (!invertDebuffs || !isPrimaryTarget) {
                        entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, cfg.musicDebuffTicks, 0));
                        if (rageMode) {
                            entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, cfg.musicDebuffTicks, 0));
                        }
                    }

                    // Quirk kept as a skill: the original knockback vector was (loc - loc) = zero, so
                    // the "knockback" never pushed anything anywhere. No NaN, simply no shove.
                    if (!(cfg.legacyQuirksEnabled && cfg.musicZeroKnockback)) {
                        Vec3 away = entity.position().subtract(shooterCow.position());
                        if (away.lengthSqr() > 1.0E-4D) {
                            Vec3 kb = away.normalize().scale(cfg.musicKnockback);
                            entity.setDeltaMovement(entity.getDeltaMovement().add(kb.x, 0.1D, kb.z));
                        }
                    }
                }
            }
            return true;
        }
    }

    // =========================================================================
    // 6. Exploding Cow Attack (Fixed 5-tick lifetime bug & terrain griefing)
    // =========================================================================
    public static void launchExplodingCow(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, LivingEntity target, boolean rageMode) {
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        Cow bombCow = EntityType.COW.create(level);
        if (bombCow == null) {
            return;
        }
        Vec3 start = shooterCow.position().add(0.0D, 0.5D, 0.0D);
        bombCow.moveTo(start.x, start.y, start.z, shooterCow.yRot, 0.0F);
        bombCow.setBaby(true);
        bombCow.setCustomName(new TextComponent(rageMode ? "§4§l狂暴自爆牛" : "§c自爆小牛"));
        bombCow.setCustomNameVisible(true);
        bombCow.setInvulnerable(true);
        bombCow.addTag("supercow_exploding_cow");
        if (ownerUuid != null) {
            bombCow.addTag("supercow_owner:" + ownerUuid.toString());
        }
        level.addFreshEntity(bombCow);
        SkillCleanup.track(bombCow, ownerUuid, cfg.explodingCowMaxLifetimeTicks + 40, "exploding_cow");

        ACTIVE_EXPLODING_COWS.add(new ExplodingCowTask(level, shooterCow, ownerUuid, bombCow, target, rageMode, cfg));
        level.playSound(null, start.x, start.y, start.z, SoundEvents.COW_HURT, SoundSource.NEUTRAL, 0.8F, 1.4F);
    }

    private static class ExplodingCowTask {
        final ServerLevel level;
        final LivingEntity shooterCow;
        final UUID ownerUuid;
        final Cow bombCow;
        final LivingEntity target;
        final boolean rageMode;
        final SuperCowConfig cfg;
        int ticks = 0;
        boolean fusing = false;
        int fuseTicks = 0;

        ExplodingCowTask(ServerLevel level, LivingEntity shooterCow, UUID ownerUuid, Cow bombCow, LivingEntity target, boolean rageMode, SuperCowConfig cfg) {
            this.level = level;
            this.shooterCow = shooterCow;
            this.ownerUuid = ownerUuid;
            this.bombCow = bombCow;
            this.target = target;
            this.rageMode = rageMode;
            this.cfg = cfg;
        }

        boolean tick() {
            ticks++;
            if (bombCow == null || !bombCow.isAlive() || bombCow.removed) {
                return false;
            }
            if (ticks >= cfg.explodingCowMaxLifetimeTicks) {
                explode();
                return false;
            }

            if (!fusing) {
                if (target == null || !target.isAlive() || bombCow.distanceTo(target) <= 2.0F) {
                    fusing = true;
                } else {
                    Vec3 delta = target.position().add(0.0D, 0.3D, 0.0D).subtract(bombCow.position());
                    if (delta.lengthSqr() > 1.0E-4D) {
                        Vec3 vel = delta.normalize().scale(cfg.explodingCowLaunchSpeed * 0.45D);
                        bombCow.setDeltaMovement(vel.x, Math.max(-0.2D, vel.y), vel.z);
                    }
                    level.sendParticles(ParticleTypes.SMOKE, bombCow.getX(), bombCow.getY() + 0.4D, bombCow.getZ(), 2, 0.1D, 0.1D, 0.1D, 0.01D);
                }
            }

            if (fusing) {
                fuseTicks++;
                bombCow.setDeltaMovement(0.0D, bombCow.getDeltaMovement().y, 0.0D);
                int remaining = Math.max(0, (cfg.explodingCowFuseTicks - fuseTicks) / 10 + 1);
                bombCow.setCustomName(new TextComponent("§c§l自爆倒计时: " + remaining));
                level.sendParticles(ParticleTypes.FLAME, bombCow.getX(), bombCow.getY() + 0.5D, bombCow.getZ(), 3, 0.2D, 0.2D, 0.2D, 0.03D);
                if (fuseTicks >= cfg.explodingCowFuseTicks) {
                    explode();
                    return false;
                }
            }
            return true;
        }

        void explode() {
            Vec3 pos = bombCow.position();
            double radius = rageMode ? cfg.rageExplodingCowRadius : cfg.explodingCowRadius;
            float baseDmg = (float) (rageMode ? cfg.rageExplodingCowDamage : cfg.explodingCowDamage);
            DamageSource src = shooterCow != null ? DamageSource.mobAttack(shooterCow) : DamageSource.GENERIC;

            AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (!TargetValidator.isValidAoETarget(entity, ownerUuid, target)) {
                    continue;
                }
                double dist = entity.position().distanceTo(pos);
                if (dist <= radius) {
                    double factor = Math.max(0.25D, 1.0D - (dist / radius));
                    entity.hurt(src, (float) (baseDmg * factor));
                    Vec3 away = entity.position().subtract(pos);
                    if (away.lengthSqr() > 1.0E-4D) {
                        Vec3 kb = away.normalize().scale(cfg.explodingCowKnockback * factor);
                        entity.setDeltaMovement(entity.getDeltaMovement().add(kb.x, 0.2D * factor, kb.z));
                    }
                }
            }

            // Safe particle + sound explosion (BlockInteraction.NONE equivalent, zero block damage)
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 0.3D, pos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + 0.3D, pos.z, 12, 0.5D, 0.4D, 0.5D, 0.08D);
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 0.9F, 1.0F);
        }

        void cleanup() {
            if (bombCow != null && !bombCow.removed) {
                bombCow.remove();
            }
            SkillCleanup.untrack(bombCow);
        }
    }
}
