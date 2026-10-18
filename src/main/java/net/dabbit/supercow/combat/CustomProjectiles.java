package net.dabbit.supercow.combat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.config.SuperCowConfig;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla-client-compatible server-side projectile subclasses.
 * Clients receive standard Arrow/SmallFireball/ThrownPotion/ThrownEgg packets,
 * while the server executes safe, bug-fixed, conservative impact logic.
 */
public final class CustomProjectiles {
    private CustomProjectiles() {
    }

    public enum ArrowEffectType {
        NONE,
        FROST,
        LIGHTNING,
        EXPLOSION,
        VORTEX,
        RAIN,
        BOUNCE
    }

    public static class SuperCowArrow extends Arrow {
        private final UUID ownerUuid;
        private final LivingEntity shooterCow;
        private final double customDamage;
        private final boolean rageMode;
        private final boolean summonArrow;
        private final ArrowEffectType effectType;
        private final int remainingBounces;
        private final Set<UUID> hitEntities = new HashSet<>();

        public SuperCowArrow(
            ServerLevel level,
            LivingEntity shooterCow,
            UUID ownerUuid,
            double customDamage,
            boolean rageMode,
            boolean summonArrow,
            ArrowEffectType effectType,
            int remainingBounces
        ) {
            super(level, shooterCow.getX(), shooterCow.getEyeY() - 0.1D, shooterCow.getZ());
            this.shooterCow = shooterCow;
            this.ownerUuid = ownerUuid;
            this.customDamage = customDamage;
            this.rageMode = rageMode;
            this.summonArrow = summonArrow;
            this.effectType = effectType != null ? effectType : ArrowEffectType.NONE;
            this.remainingBounces = remainingBounces;
            this.setOwner(shooterCow);
            this.pickup = AbstractArrow.Pickup.DISALLOWED;
            this.setBaseDamage(customDamage);
            if (ownerUuid != null) {
                this.addTag("supercow_owner:" + ownerUuid.toString());
            }
            this.addTag(summonArrow ? "supercow_summon_arrow" : "supercow_pet_arrow");
        }

        @Override
        public void tick() {
            super.tick();
            if (!this.level.isClientSide && this.tickCount > 100) {
                this.remove();
            }
        }

        @Override
        protected boolean canHitEntity(Entity target) {
            if (TargetValidator.isFriendlyEntity(target, ownerUuid)) {
                return false;
            }
            return super.canHitEntity(target);
        }

        @Override
        protected void onHitEntity(EntityHitResult result) {
            if (this.level.isClientSide) {
                return;
            }
            Entity hit = result.getEntity();
            if (!(hit instanceof LivingEntity)) {
                this.remove();
                return;
            }
            LivingEntity target = (LivingEntity) hit;
            if (TargetValidator.isFriendlyEntity(target, ownerUuid)) {
                this.remove();
                return;
            }

            hitEntities.add(target.getUUID());
            DamageSource src = DamageSource.arrow(this, shooterCow != null ? shooterCow : this);
            target.hurt(src, (float) customDamage);

            if (this.level instanceof ServerLevel) {
                ServerLevel serverLevel = (ServerLevel) this.level;
                serverLevel.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(), 4, 0.2D, 0.2D, 0.2D, 0.05D);
                if (!summonArrow && effectType != ArrowEffectType.NONE) {
                    SpecialAttackManagers.applyArrowEffect(
                        serverLevel,
                        shooterCow,
                        ownerUuid,
                        target,
                        target.position(),
                        effectType,
                        rageMode,
                        customDamage,
                        remainingBounces,
                        hitEntities
                    );
                }
            }
            this.remove();
        }

        @Override
        protected void onHitBlock(BlockHitResult result) {
            super.onHitBlock(result);
            if (!this.level.isClientSide) {
                if (!summonArrow && effectType != ArrowEffectType.NONE && this.level instanceof ServerLevel) {
                    Vec3 hitPos = result.getLocation();
                    SpecialAttackManagers.applyArrowGroundEffect(
                        (ServerLevel) this.level,
                        shooterCow,
                        ownerUuid,
                        hitPos,
                        effectType,
                        rageMode
                    );
                }
                this.remove();
            }
        }
    }

    public static class SuperCowFireball extends SmallFireball {
        private final UUID ownerUuid;
        private final LivingEntity shooterCow;
        private final boolean rageMode;

        public SuperCowFireball(
            ServerLevel level,
            LivingEntity shooterCow,
            UUID ownerUuid,
            double dx,
            double dy,
            double dz,
            boolean rageMode
        ) {
            super(level, shooterCow, dx, dy, dz);
            this.shooterCow = shooterCow;
            this.ownerUuid = ownerUuid;
            this.rageMode = rageMode;
            this.setPos(shooterCow.getX(), shooterCow.getEyeY(), shooterCow.getZ());
            if (ownerUuid != null) {
                this.addTag("supercow_owner:" + ownerUuid.toString());
            }
            this.addTag("supercow_fireball");
        }

        @Override
        public void tick() {
            super.tick();
            if (!this.level.isClientSide && this.tickCount > 80) {
                this.remove();
            }
        }

        @Override
        protected void onHitEntity(EntityHitResult result) {
            if (this.level.isClientSide) {
                return;
            }
            Entity hit = result.getEntity();
            if (hit instanceof LivingEntity && !TargetValidator.isFriendlyEntity(hit, ownerUuid)) {
                LivingEntity target = (LivingEntity) hit;
                SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
                float dmg = (float) (rageMode ? cfg.rageFireballDamage : cfg.fireballDamage);
                int fireSeconds = (rageMode ? cfg.rageFireballFireTicks : cfg.fireballFireTicks) / 20;
                target.setSecondsOnFire(Math.max(1, fireSeconds));
                target.hurt(DamageSource.fireball(this, shooterCow != null ? shooterCow : this), dmg);
            }
        }

        @Override
        protected void onHitBlock(BlockHitResult result) {
            // Do NOT ignite blocks in the world (conservative & grief-safe)
        }

        @Override
        protected void onHit(HitResult result) {
            if (this.level.isClientSide) {
                return;
            }
            if (result.getType() == HitResult.Type.ENTITY) {
                this.onHitEntity((EntityHitResult) result);
            }
            if (this.level instanceof ServerLevel) {
                ServerLevel serverLevel = (ServerLevel) this.level;
                Vec3 pos = result.getLocation();
                serverLevel.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 10, 0.3D, 0.3D, 0.3D, 0.05D);
                serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 6, 0.25D, 0.25D, 0.25D, 0.02D);
                serverLevel.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 0.6F, 1.2F);
            }
            this.remove();
        }
    }

    public static class SuperCowPotion extends ThrownPotion {
        private final UUID ownerUuid;
        private final LivingEntity shooterCow;
        private final boolean rageMode;
        private final LivingEntity intendedTarget;

        public SuperCowPotion(
            ServerLevel level,
            LivingEntity shooterCow,
            UUID ownerUuid,
            LivingEntity intendedTarget,
            boolean rageMode
        ) {
            super(level, shooterCow);
            this.shooterCow = shooterCow;
            this.ownerUuid = ownerUuid;
            this.intendedTarget = intendedTarget;
            this.rageMode = rageMode;
            if (ownerUuid != null) {
                this.addTag("supercow_owner:" + ownerUuid.toString());
            }
            this.addTag("supercow_potion");
        }

        @Override
        protected void onHit(HitResult result) {
            if (this.level.isClientSide) {
                return;
            }
            if (this.level instanceof ServerLevel) {
                ServerLevel serverLevel = (ServerLevel) this.level;
                SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
                Vec3 pos = result.getLocation();
                double radius = rageMode ? cfg.ragePotionSplashRadius : cfg.potionSplashRadius;
                float directDmg = (float) (rageMode ? cfg.ragePotionDirectDamage : cfg.potionDirectDamage);
                int duration = cfg.potionEffectDurationTicks;

                AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);
                List<LivingEntity> entities = serverLevel.getEntitiesOfClass(LivingEntity.class, box);
                for (LivingEntity entity : entities) {
                    if (!TargetValidator.isValidAoETarget(entity, ownerUuid, intendedTarget)) {
                        continue;
                    }
                    double dist = entity.position().distanceTo(pos);
                    if (dist > radius) {
                        continue;
                    }
                    double scale = Math.max(0.25D, 1.0D - (dist / radius));
                    int scaledTicks = Math.max(20, (int) (duration * scale));
                    entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, scaledTicks, 0));
                    entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, scaledTicks, 0));
                    if (rageMode) {
                        entity.addEffect(new MobEffectInstance(MobEffects.POISON, Math.max(20, scaledTicks / 2), 0));
                    }
                    entity.hurt(DamageSource.indirectMagic(this, shooterCow != null ? shooterCow : this), (float) (directDmg * scale));
                }

                serverLevel.sendParticles(ParticleTypes.WITCH, pos.x, pos.y + 0.2D, pos.z, 12, 0.4D, 0.3D, 0.4D, 0.05D);
                serverLevel.playSound(null, pos.x, pos.y, pos.z, SoundEvents.SPLASH_POTION_BREAK, SoundSource.NEUTRAL, 0.8F, 1.0F);
            }
            this.remove();
        }
    }

    public static class SuperCowEgg extends ThrownEgg {
        private final UUID ownerUuid;
        private final LivingEntity shooterCow;
        private final LivingEntity intendedTarget;
        private final boolean rageMode;
        /** original bug kept as a skill: eggs only damaged players (the owner included) */
        private final boolean quirkPlayersOnly;

        public SuperCowEgg(
            ServerLevel level,
             double x,
            double y,
            double z,
            LivingEntity shooterCow,
            UUID ownerUuid,
            LivingEntity intendedTarget,
            boolean rageMode,
            boolean quirkPlayersOnly
        ) {
            super(level, x, y, z);
            this.quirkPlayersOnly = quirkPlayersOnly;
            this.shooterCow = shooterCow;
            this.ownerUuid = ownerUuid;
            this.intendedTarget = intendedTarget;
            this.rageMode = rageMode;
            if (shooterCow != null) {
                this.setOwner(shooterCow);
            }
            if (ownerUuid != null) {
                this.addTag("supercow_owner:" + ownerUuid.toString());
            }
            this.addTag("supercow_bomb_egg");
        }

        @Override
        protected void onHitEntity(EntityHitResult result) {
            // Handled uniformly in onHit to avoid vanilla chicken spawning
        }

        @Override
        protected void onHit(HitResult result) {
            if (this.level.isClientSide) {
                return;
            }
            if (this.level instanceof ServerLevel) {
                ServerLevel serverLevel = (ServerLevel) this.level;
                SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
                Vec3 pos = result.getLocation();
                double radius = cfg.eggExplosionRadius;
                float baseDmg = (float) (rageMode ? cfg.rageEggExplosionDamage : cfg.eggExplosionDamage);

                AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);
                boolean playersOnlyQuirk = quirkPlayersOnly && cfg.eggQuirkPlayersOnly;
                List<LivingEntity> nearby = serverLevel.getEntitiesOfClass(LivingEntity.class, box);
                for (LivingEntity entity : nearby) {
                    if (playersOnlyQuirk) {
                        // Quirk kept as a skill: the original egg impact handler bailed out on anything
                        // that was not a Player, so the bombardment only ever hurt players - owner included.
                        if (!(entity instanceof Player) || entity == shooterCow) {
                            continue;
                        }
                    } else if (!TargetValidator.isValidAoETarget(entity, ownerUuid, intendedTarget)) {
                        // FIXED BUG: the repaired path damages hostiles and never the owner's party.
                        continue;
                    }
                    double dist = entity.position().distanceTo(pos);
                    if (dist > radius) {
                        continue;
                    }
                    double factor = Math.max(0.3D, 1.0D - (dist / radius));
                    entity.hurt(DamageSource.thrown(this, shooterCow != null ? shooterCow : this), (float) (baseDmg * factor));
                    entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, cfg.eggSlownessTicks, 0));
                }

                serverLevel.sendParticles(
                    new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.EGG)),
                    pos.x, pos.y + 0.1D, pos.z,
                    8, 0.25D, 0.2D, 0.25D, 0.08D
                );
                serverLevel.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.1D, pos.z, 4, 0.2D, 0.1D, 0.2D, 0.02D);
                serverLevel.playSound(null, pos.x, pos.y, pos.z, SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 0.7F, 0.8F);
            }
            this.remove();
        }
    }
}
