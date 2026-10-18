package net.dabbit.supercow.pathfinding;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.config.SuperCowConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Movement manager for SuperCow.
 *
 * <p>The main pet uses the original plugin's "abstract" movement: no A* path, no navigation
 * points, just a per-tick velocity vector that is rebuilt every tick from distance bands,
 * a drifting strafe angle, obstacle rotation, jumping and a small random jump. This is
 * deliberately kept janky (it hugs walls, circles targets and hops around) because that
 * behaviour is part of the character of the original plugin.</p>
 *
 * <p>Summoned helpers keep using vanilla navigation, so they still behave like normal mobs.</p>
 */
public class PathfindingManager {
    private static final Random RANDOM = new Random();

    private static class PathState {
        final Mob mob;
        Vec3 targetPos;
        Entity targetEntity;
        double speedModifier;
        Vec3 lastPos;
        int stuckTicks;
        int ticksActive;
        boolean legacy;
        double strafeAngle;
        int lineOfSightTimer;
        boolean cachedLineOfSight = true;
        int jumpCooldown;

        PathState(Mob mob, Vec3 targetPos, Entity targetEntity, double speedModifier, boolean legacy) {
            this.mob = mob;
            this.targetPos = targetPos;
            this.targetEntity = targetEntity;
            this.speedModifier = speedModifier;
            this.lastPos = mob.position();
            this.stuckTicks = 0;
            this.ticksActive = 0;
            this.legacy = legacy;
            this.strafeAngle = RANDOM.nextDouble() * 360.0D;
        }
    }

    private final Map<UUID, PathState> activePaths = new HashMap<>();

    private SuperCowConfig config() {
        SuperCowMod mod = SuperCowMod.getInstance();
        return mod == null ? null : mod.getConfig();
    }

    private boolean useLegacyMovement(Mob mob) {
        SuperCowConfig cfg = config();
        return cfg != null && cfg.legacyMovementEnabled && mob.getTags().contains("supercow_pet");
    }

    public void moveToEntity(Mob mob, Entity target, double speedModifier) {
        if (mob == null || target == null || !mob.isAlive()) {
            return;
        }
        faceEntity(mob, target);
        boolean legacy = useLegacyMovement(mob);
        PathState state = activePaths.get(mob.getUUID());
        if (state == null) {
            state = new PathState(mob, target.position(), target, speedModifier, legacy);
            activePaths.put(mob.getUUID(), state);
        } else {
            state.targetEntity = target;
            state.targetPos = target.position();
            state.speedModifier = speedModifier;
            state.legacy = legacy;
        }
        if (!legacy) {
            mob.getNavigation().moveTo(target, speedModifier);
        } else {
            mob.getNavigation().stop();
        }
    }

    public void moveToPosition(Mob mob, Vec3 pos, double speedModifier) {
        if (mob == null || pos == null || !mob.isAlive()) {
            return;
        }
        facePosition(mob, pos);
        boolean legacy = useLegacyMovement(mob);
        PathState state = activePaths.get(mob.getUUID());
        if (state == null) {
            state = new PathState(mob, pos, null, speedModifier, legacy);
            activePaths.put(mob.getUUID(), state);
        } else {
            state.targetEntity = null;
            state.targetPos = pos;
            state.speedModifier = speedModifier;
            state.legacy = legacy;
        }
        if (!legacy) {
            mob.getNavigation().moveTo(pos.x, pos.y, pos.z, speedModifier);
        } else {
            mob.getNavigation().stop();
        }
    }

    public void stopPathfinding(Mob mob) {
        if (mob == null) {
            return;
        }
        // Stop navigation first so a vanilla path cannot keep pushing after the state is gone.
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

            if (state.legacy) {
                tickLegacyMover(state);
            } else if (!tickVanillaMover(state)) {
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ vanilla helpers

    private boolean tickVanillaMover(PathState state) {
        Mob mob = state.mob;
        double distSqr = mob.position().distanceToSqr(state.targetPos);
        if (distSqr <= 2.25D) {
            mob.getNavigation().stop();
            return false;
        }
        if (state.ticksActive % 10 == 0) {
            if (state.targetEntity != null) {
                mob.getNavigation().moveTo(state.targetEntity, state.speedModifier);
            } else {
                mob.getNavigation().moveTo(state.targetPos.x, state.targetPos.y, state.targetPos.z, state.speedModifier);
            }
        }
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
        return true;
    }

    // ------------------------------------------------------------------ legacy "abstract" movement

    /**
     * Rebuilds the movement vector exactly like the Bukkit plugin did: distance-band speed,
     * drifting strafe, line-of-sight detour, obstacle rotation and jumping. The result is
     * written straight into the entity velocity.
     */
    private void tickLegacyMover(PathState state) {
        SuperCowConfig cfg = config();
        Mob mob = state.mob;
        if (cfg == null) {
            return;
        }
        double maxSpeed = cfg.legacyMoveSpeed;
        Vec3 pos = mob.position();
        double dist = pos.distanceTo(state.targetPos);

        if (dist < 2.0D) {
            // Inside the fight: stop pushing forward but let the strafe wiggle continue.
            mob.getNavigation().stop();
            applyVelocity(mob, 0.0D, 0.0D, mob.getDeltaMovement().y, maxSpeed);
            return;
        }

        state.lineOfSightTimer--;
        if (state.lineOfSightTimer <= 0) {
            state.lineOfSightTimer = 5;
            state.cachedLineOfSight = hasLineOfSight(mob, state.targetPos);
        }

        Vec3 direction;
        if (state.cachedLineOfSight) {
            direction = state.targetPos.subtract(pos);
        } else {
            direction = alternativeDirection(mob, state.targetPos, cfg);
        }

        double speed;
        double optimal = cfg.legacyOptimalDistance;
        if (dist > optimal * 1.5D) {
            speed = maxSpeed;
        } else if (dist < optimal * 0.5D) {
            speed = 0.0D;
        } else {
            speed = Mth.clamp((dist - optimal) * (maxSpeed / 6.0D), 0.0D, maxSpeed);
        }
        speed *= Math.min(1.5D, Math.max(0.35D, state.speedModifier));

        Vec3 flat = new Vec3(direction.x, 0.0D, direction.z);
        if (flat.lengthSqr() > 1.0E-4D) {
            flat = flat.normalize().scale(speed);
        }

        // The original plugin drifted the strafe angle forever: +10 degrees per tick, or +-45
        // degrees on a 10% roll. That is why the pet circles instead of walking straight.
        state.strafeAngle += RANDOM.nextDouble() < 0.1D ? (RANDOM.nextBoolean() ? 45.0D : -45.0D) : 10.0D;
        double rad = Math.toRadians(state.strafeAngle);
        double strafe = cfg.legacyStrafeStrength;
        double vx = flat.x + Math.cos(rad) * strafe;
        double vz = flat.z + Math.sin(rad) * strafe;

        double vy = mob.getDeltaMovement().y;
        if (mob.isOnGround()) {
            vy = 0.0D;
            Vec3 ahead = pos.add(new Vec3(direction.x, 0.0D, direction.z).normalize().scale(1.2D));
            BlockPos frontPos = new BlockPos(ahead.x, pos.y + 0.1D, ahead.z);
            boolean blocked = mob.level.getBlockState(frontPos).getMaterial().isSolid();
            boolean headClear = !mob.level.getBlockState(new BlockPos(pos.x, pos.y + 2.0D, pos.z)).getMaterial().isSolid();
            if (blocked && headClear) {
                vy = cfg.legacyJumpPower;
            } else if (state.jumpCooldown <= 0 && RANDOM.nextDouble() < 0.03D) {
                // The original had a commented-out random hop; keep it as a comedic quirk.
                vy = cfg.legacyJumpPower * 0.8D;
                state.jumpCooldown = 40;
            }
        }
        if (state.jumpCooldown > 0) {
            state.jumpCooldown--;
        }

        applyVelocity(mob, vx, vz, vy, maxSpeed);

        // Stuck handling: the strafing pet can wedge itself into a corner. After 2s of no
        // progress on a long chase the pet blinks closer (same safety rule as before).
        if (state.ticksActive % 10 == 0) {
            double movedSqr = mob.position().distanceToSqr(state.lastPos);
            state.lastPos = mob.position();
            if (movedSqr < 0.02D && dist > 6.0D) {
                state.stuckTicks++;
            } else {
                state.stuckTicks = 0;
            }
            if (state.stuckTicks > 6 && dist > 16.0D) {
                BlockPos safePos = new BlockPos(state.targetPos.x, state.targetPos.y, state.targetPos.z);
                if (mob.level.getBlockState(safePos).getMaterial().isReplaceable()) {
                    mob.teleportTo(state.targetPos.x, state.targetPos.y, state.targetPos.z);
                    state.stuckTicks = 0;
                }
            }
        }
    }

    private void applyVelocity(Mob mob, double vx, double vz, double vy, double maxSpeed) {
        double limit = Math.max(0.05D, maxSpeed * 1.2D);
        double dx = Mth.clamp(finite(vx), -limit, limit);
        double dy = Mth.clamp(finite(vy), -1.0D, 1.0D);
        double dz = Mth.clamp(finite(vz), -limit, limit);
        if (!mob.isNoAi()) {
            mob.setDeltaMovement(dx, dy, dz);
            return;
        }
        // A no-AI mob does not run vanilla travel in 1.16.5: its velocity is never applied and it
        // neither walks nor falls. The "abstract" mover therefore writes the vector and displaces
        // the entity itself, including a hand-rolled gravity/step-up so it can walk on terrain.
        if (!mob.isOnGround()) {
            double fall = Math.min(dy, 0.0D) - 0.08D;
            dy = Math.max(fall, -0.6D);
        } else if (dy < 0.0D) {
            dy = 0.0D;
        }
        mob.setDeltaMovement(dx, dy, dz);
        mob.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(dx, dy, dz));
    }

    private static double finite(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return value;
    }

    private Vec3 alternativeDirection(Mob mob, Vec3 targetPos, SuperCowConfig cfg) {
        Vec3 pos = mob.position();
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        double radius = cfg.legacyOptimalDistance;
        for (int angle = 0; angle < 360; angle += 45) {
            double rad = Math.toRadians(angle);
            Vec3 candidate = new Vec3(
                pos.x + Math.cos(rad) * radius,
                pos.y,
                pos.z + Math.sin(rad) * radius
            );
            if (!hasLineOfSightFrom(mob, candidate, targetPos)) {
                continue;
            }
            if (!isLocationSafe(mob, candidate)) {
                continue;
            }
            double d = candidate.distanceTo(targetPos);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        if (best == null) {
            return targetPos.subtract(pos);
        }
        return best.subtract(pos);
    }

    private static boolean hasLineOfSight(Mob mob, Vec3 targetPos) {
        return hasLineOfSightFrom(mob, mob.position(), targetPos);
    }

    private static boolean hasLineOfSightFrom(Mob mob, Vec3 from, Vec3 to) {
        Vec3 eye = new Vec3(from.x, from.y + 1.0D, from.z);
        BlockHitResult hit = mob.level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean isLocationSafe(Mob mob, Vec3 location) {
        BlockPos base = new BlockPos(location.x, location.y, location.z);
        BlockState feet = mob.level.getBlockState(base);
        BlockState above = mob.level.getBlockState(base.above());
        BlockState below = mob.level.getBlockState(base.below());
        return !feet.getMaterial().isSolid()
            && !above.getMaterial().isSolid()
            && below.getMaterial().isSolid()
            && !below.getMaterial().isLiquid();
    }

    /**
     * Applies yaw/pitch straight to the entity (the original mutated a cloned Location and the
     * rotation never reached the cow; the pet staring at nothing is preserved only as flavour,
     * not as a real bug).
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
