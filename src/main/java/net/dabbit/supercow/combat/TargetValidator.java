package net.dabbit.supercow.combat;

import java.util.Set;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;

/**
 * Centralized target validation and friendly-fire prevention.
 * Fixes bugs where music attacks, egg bombardments, exploding cows,
 * and summon arrows damaged the owner, pet cows, or friendly entities.
 */
public final class TargetValidator {
    private TargetValidator() {
    }

    public static boolean isManagedConstruct(Entity entity) {
        if (entity == null) {
            return false;
        }
        Set<String> tags = entity.getTags();
        return tags.contains("supercow_quirk_actor")
            || tags.contains("supercow_pet")
            || tags.contains("supercow_summon")
            || tags.contains("supercow_bomber_chicken")
            || tags.contains("supercow_exploding_cow");
    }

    public static boolean isFriendlyEntity(Entity entity, UUID ownerUuid) {
        if (entity == null) {
            return true;
        }
        if (ownerUuid != null && ownerUuid.equals(entity.getUUID())) {
            return true;
        }
        if (isManagedConstruct(entity)) {
            return true;
        }
        if (entity instanceof ArmorStand || entity instanceof AbstractVillager || entity instanceof Bat) {
            return true;
        }
        if (entity instanceof TamableAnimal) {
            TamableAnimal tamable = (TamableAnimal) entity;
            if (tamable.isTame() && ownerUuid != null && ownerUuid.equals(tamable.getOwnerUUID())) {
                return true;
            }
        }
        if (entity instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) entity;
            if (player.isCreative() || player.isSpectator()) {
                return true;
            }
            if (ownerUuid != null && ownerUuid.equals(player.getUUID())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidCombatTarget(LivingEntity candidate, UUID ownerUuid, LivingEntity petCow, boolean allowNonMonsterIfProvoked) {
        if (candidate == null || !candidate.isAlive() || candidate.removed || candidate.isDeadOrDying()) {
            return false;
        }
        // A dying entity keeps a corpse entity around for its death animation; never treat it as a target.
        if (candidate.getHealth() <= 0.0F) {
            return false;
        }
        if (petCow != null && candidate.getUUID().equals(petCow.getUUID())) {
            return false;
        }
        if (isFriendlyEntity(candidate, ownerUuid)) {
            return false;
        }
        if (candidate instanceof Player) {
            // Never automatically target players unless explicitly provoked PvP (disabled by default for safety)
            return false;
        }
        if (candidate instanceof Monster || candidate instanceof Enemy) {
            return true;
        }
        if (allowNonMonsterIfProvoked && (candidate instanceof Animal || candidate instanceof IronGolem || candidate instanceof SnowGolem)) {
            return true;
        }
        return false;
    }

    public static boolean isValidAoETarget(LivingEntity candidate, UUID ownerUuid, LivingEntity explicitTarget) {
        if (candidate == null || !candidate.isAlive() || candidate.removed || candidate.isDeadOrDying()) {
            return false;
        }
        if (candidate.getHealth() <= 0.0F) {
            return false;
        }
        if (isFriendlyEntity(candidate, ownerUuid)) {
            return false;
        }
        if (explicitTarget != null && candidate.getUUID().equals(explicitTarget.getUUID())) {
            return true;
        }
        return candidate instanceof Monster || candidate instanceof Enemy;
    }

    public static UUID resolveAttackerOwnerUuid(Entity directEntity, Entity sourceEntity) {
        if (directEntity != null) {
            UUID tagOwner = SuperCowMod.getOwnerUuidFromTags(directEntity);
            if (tagOwner != null) {
                return tagOwner;
            }
        }
        if (sourceEntity != null) {
            UUID tagOwner = SuperCowMod.getOwnerUuidFromTags(sourceEntity);
            if (tagOwner != null) {
                return tagOwner;
            }
            if (sourceEntity instanceof Player) {
                return sourceEntity.getUUID();
            }
        }
        return null;
    }
}
