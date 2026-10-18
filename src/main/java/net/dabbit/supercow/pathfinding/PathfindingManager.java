package net.dabbit.supercow.pathfinding;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Synchronous server-thread pathfinding and rotation manager for SuperCow.
 * Fixes:
 * 1. Off-thread async world/block access in original PathfindingManager.
 * 2. stopPathfinding calling cleanup() before cancelling active state.
 * 3. Zero-vector normalize() NaN velocity when entity is already at target.
 * 4. faceCowToTarget modifying a cloned location without applying rotation to the entity.
 */
public class PathfindingManager {
    private static class PathState {
        final Mob mob;
        Vec3 targetPos;
        Entity targetEntity;
        double speedModifier;
        Vec3 lastPos;
        int stuckTicks;
        int ticksActive;

        PathState(Mob mob, Vec3 targetPos, Entity targetEntity, double speedModifier) {
            this.mob = mob;
            this.targetPos = targetPos;
            this.targetEntity = targetEntity;
            this.speedModifier = speedModifier;
            this.lastPos = mob.position();
            this.stuckTicks = 0;
            this.ticksActive = 0;
        }
    }

    private final Map<UUID, PathState> activePaths = new HashMap<>();

    public void moveToEntity(Mob mob, Entity target, double speedModifier) {
        if (mob == null || target == null || !mob.isAlive()) {
            return;
        }
        faceEntity(mob, target);
        PathState state = activePaths.get(mob.getUUID());
        if (state == null) {
            state = new PathState(mob, target.position(), target, speedModifier);
            activePaths.put(mob.getUUID(), state);
        } else {
            state.targetEntity = target;
            state.targetPos = target.position();
            state.speedModifier = speedModifier;
        }
        mob.getNavigation().moveTo(target, speedModifier);
    }

    public void moveToPosition(Mob mob, Vec3 pos, double speedModifier) {
        if (mob == null || pos == null || !mob.isAlive()) {
            return;
        }
        facePosition(mob, pos);
        PathState state = activePaths.get(mob.getUUID());
        if (state == null) {
            state = new PathState(mob, pos, null, speedModifier);
            activePaths.put(mob.getUUID(), state);
        } else {
            state.targetEntity = null;
            state.targetPos = pos;
            state.speedModifier = speedModifier;
        }
        mob.getNavigation().moveTo(pos.x, pos.y, pos.z, speedModifier);
    }

    public void stopPathfinding(Mob mob) {
        if (mob == null) {
            return;
        }
        // FIXED BUG: Stop navigation FIRST before removing state
        mob.getNavigation().stop();
        activePaths.remove(mob.getUUID());
    }

    public void stopPathfinding(UUID mobUuid) {
        if (mobUuid != null) {
            activePaths.remove(mobUuid);
        }
    }

    public void cleanupAll() {
        for (PathState state : activePaths.values()) {
            if (state.mob != null && state.mob.isAlive()) {
                state.mob.getNavigation().stop();
            }
        }
        activePaths.clear();
    }

    public void tick() {
        Iterator<Map.Entry<UUID, PathState>> it = activePaths.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PathState> entry = it.next();
            PathState state = entry.getValue();
            Mob mob = state.mob;
            if (mob == null || !mob.isAlive() || mob.removed) {
                it.remove();
                continue;
            }
            state.ticksActive++;
            if (state.targetEntity != null) {
                if (!state.targetEntity.isAlive() || state.targetEntity.removed) {
                    mob.getNavigation().stop();
                    it.remove();
                    continue;
                }
                state.targetPos = state.targetEntity.position();
            }
            if (state.targetPos == null) {
                it.remove();
                continue;
            }

            double distSqr = mob.position().distanceToSqr(state.targetPos);
            if (distSqr <= 2.25D) {
                mob.getNavigation().stop();
                it.remove();
                continue;
            }

            if (state.ticksActive % 10 == 0) {
                if (state.targetEntity != null) {
                    mob.getNavigation().moveTo(state.targetEntity, state.speedModifier);
                } else {
                    mob.getNavigation().moveTo(state.targetPos.x, state.targetPos.y, state.targetPos.z, state.speedModifier);
                }
            }

            // Stuck check every 15 ticks with safe non-zero vector normalization
            if (state.ticksActive % 15 == 0) {
                double movedSqr = mob.position().distanceToSqr(state.lastPos);
                state.lastPos = mob.position();
                if (movedSqr < 0.04D && distSqr > 4.0D) {
                    state.stuckTicks++;
                    Vec3 diff = state.targetPos.subtract(mob.position());
                    double horizLenSqr = diff.x * diff.x + diff.z * diff.z;
                    if (horizLenSqr > 1.0E-4D) {
                        double invLen = 1.0D / Math.sqrt(horizLenSqr);
                        double stepX = diff.x * invLen * 0.22D;
                        double stepZ = diff.z * invLen * 0.22D;
                        double stepY = mob.isOnGround() ? 0.38D : mob.getDeltaMovement().y;
                        mob.setDeltaMovement(stepX, stepY, stepZ);
                    }
                    if (state.stuckTicks > 6 && distSqr > 144.0D) {
                        BlockPos safePos = new BlockPos(state.targetPos.x, state.targetPos.y, state.targetPos.z);
                        if (mob.level.getBlockState(safePos).getMaterial().isReplaceable()) {
                            mob.teleportTo(state.targetPos.x, state.targetPos.y, state.targetPos.z);
                            state.stuckTicks = 0;
                        }
                    }
                } else {
                    state.stuckTicks = 0;
                }
            }
        }
    }

    /**
     * FIXED BUG: Original faceCowToTarget modified a cloned Location's direction
     * without applying yaw/pitch back to the entity.
     */
    public static void faceEntity(Mob mob, Entity target) {
        if (mob == null || target == null) {
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        double dy = (target.getY() + target.getBbHeight() * 0.5D) - (mob.getY() + mob.getEyeHeight());
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz > 1.0E-4D) {
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
            float pitch = (float) (-(Mth.atan2(dy, horiz) * (180.0D / Math.PI)));
            mob.yRot = yaw;
            mob.yHeadRot = yaw;
            mob.yBodyRot = yaw;
            mob.xRot = pitch;
        }
    }

    public static void facePosition(Mob mob, Vec3 pos) {
        if (mob == null || pos == null) {
            return;
        }
        mob.getLookControl().setLookAt(pos.x, pos.y, pos.z, 30.0F, 30.0F);
        double dx = pos.x - mob.getX();
        double dz = pos.z - mob.getZ();
        if (dx * dx + dz * dz > 1.0E-4D) {
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
            mob.yRot = yaw;
            mob.yHeadRot = yaw;
            mob.yBodyRot = yaw;
        }
    }
}
