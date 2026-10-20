package net.dabbit.supercow.combat;

import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.config.SuperCowConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Central outgoing balance; never infer the current attacker from kill-credit history. */
public final class CombatBalance {
    private CombatBalance() {
    }

    public static float scaleOutgoingDamage(SuperCowMod mod, LivingEntity victim, DamageSource source, float amount) {
        if (mod == null || victim == null || source == null || victim.level == null
            || victim.level.isClientSide || !(amount > 0.0F) || !Float.isFinite(amount)) {
            return amount;
        }

        Entity attacker = source.getEntity();
        // A player remains a player even when they own a SuperCow (or have stale kill credit).
        // In particular, do not classify the owner's ordinary arrows as pet damage.
        if (attacker instanceof Player) {
            return amount;
        }
        if (!isOwnedAttackEntity(attacker) && !isOwnedAttackEntity(source.getDirectEntity())) {
            return amount;
        }

        SuperCowConfig config = mod.getConfig();
        if (config == null) {
            return amount;
        }
        double multiplier = boundedMultiplier(config.outgoingDamageMultiplier, 0.2D);
        if (isBoss(victim, config)) {
            multiplier *= boundedMultiplier(config.bossDamageMultiplier, 0.75D);
        }
        // Source and direct entity can both be tagged; they select this branch, not two multipliers.
        return (float) (amount * multiplier);
    }

    public static boolean isBoss(LivingEntity victim, SuperCowConfig config) {
        double threshold = config.bossMaxHealthThreshold;
        if (!Double.isFinite(threshold) || threshold < 0.0D) threshold = 400.0D;
        return victim != null && victim.getMaxHealth() > threshold;
    }

    public static double knockbackFactor(DamageSource source) {
        if (source == null || source.getEntity() instanceof Player) return 1.0D;
        return isOwnedAttackEntity(source.getEntity()) || isOwnedAttackEntity(source.getDirectEntity())
            ? impulseFactor() : 1.0D;
    }

    private static double impulseFactor() {
        SuperCowMod mod = SuperCowMod.getInstance();
        return mod == null ? 0.2D : boundedMultiplier(mod.getConfig().extraKnockbackMultiplier, 0.2D);
    }

    public static void push(Entity victim, double x, double y, double z) {
        double f = impulseFactor();
        victim.push(x * f, y * f, z * f);
    }

    public static void setVelocity(Entity victim, net.minecraft.world.phys.Vec3 desired) {
        net.minecraft.world.phys.Vec3 old = victim.getDeltaMovement();
        victim.setDeltaMovement(old.add(desired.subtract(old).scale(impulseFactor())));
    }

    public static void setVelocity(Entity victim, double x, double y, double z) {
        setVelocity(victim, new net.minecraft.world.phys.Vec3(x, y, z));
    }

    private static boolean isOwnedAttackEntity(Entity entity) {
        // This reads only live source tags (including a projectile's owner chain).
        // Victim-based recordDamageOwner is intentionally NOT consulted: it survives past hits.
        return entity != null && !(entity instanceof Player)
            && SuperCowMod.getOwnerUuidFromTags(entity) != null;
    }

    private static double boundedMultiplier(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0D ? Math.min(1.0D, value) : fallback;
    }
}
