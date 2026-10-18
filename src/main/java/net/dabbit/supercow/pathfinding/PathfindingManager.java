package net.dabbit.supercow.pathfinding;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.combat.Quirks;
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
        UUID spiralRolledTarget;
        long lastSpiralEndTick;

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

    /** A "flew into the sky" episode: the original mover could push the pet straight up. */
    private static class SkySpiral {
        final PathState state;
        final long startTick;
        final long endTick;
        final double anchorY;
        final double baseAngle;
        final boolean standalone;

        SkySpiral(PathState state, long startTick, long endTick, double anchorY, double baseAngle) {
            this.state = state;
            this.startTick = startTick;
            this.endTick = endTick;
            this.anchorY = anchorY;
            this.baseAngle = baseAngle;
            this.standalone = state == null || state.targetEntity == null;
        }
    }

    private final Map<UUID, PathState> activePaths = new HashMap<>();
    private final Map<UUID, SkySpiral> spirals = new HashMap<>();

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
        spirals.remove(mob.getUUID());
    }

    public void stopPathfinding(UUID mobUuid) {
        if (mobUuid != null) {
            activePaths.remove(mobUuid);
            spirals.remove(mobUuid);
        }
    }

    public void cleanupAll() {
        for (PathState state : activePaths.values()) {
            if (state.mob != null && state.mob.isAlive()) {
                state.mob.getNavigation().stop();
            }
        }
        activePaths.clear();
        spirals.clear();
    }

    /** Whether the legacy mover owns this mob's movement right now. */
    public boolean hasActivePath(UUID mobUuid) {
        return mobUuid != null && activePaths.containsKey(mobUuid);
    }

    /**
     * Idle physics for a no-AI pet with no active route. A no-AI mob never runs vanilla gravity,
     * so a pet left in the air (after a sky spiral, a teleport or a bad spawn) would hover forever
     * unless the mover keeps applying the hand-rolled fall.
     */
    public void applyIdleGravity(Mob mob) {
        SuperCowConfig cfg = config();
        if (mob == null || cfg == null || mob.isOnGround()) {
            return;
        }
        applyVelocity(mob, 0.0D, 0.0D, mob.getDeltaMovement().y, cfg.legacyMoveSpeed);
    }

    /** True while the pet is in the middle of the "spiral into the sky" quirk. */
    public boolean isSpiraling(UUID mobUuid) {
        return mobUuid != null && spirals.containsKey(mobUuid);
    }

    /** /supercow skill sky_spiral: force the quirk so it can be demonstrated on demand. */
    public boolean forceSkySpiral(Mob mob) {
        if (mob == null || !mob.isAlive()) {
            return false;
        }
        PathState state = activePaths.get(mob.getUUID());
        if (state == null) {
            // A forced spiral does not need a chase target, but the legacy mover (which owns the
            // spiral tick) only runs for a pet that has a path state.
            state = new PathState(mob, mob.position(), null, 1.0D, true);
            activePaths.put(mob.getUUID(), state);
        }
        startSkySpiral(mob, state, SuperCowMod.getServerTicksStatic());
        return true;
    }

    public void tick() {
        Iterator<Map.Entry<UUID, PathState>> it = activePaths.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PathState> entry = it.next();
            PathState state = entry.getValue();
            Mob mob = state.mob;
            if (mob == null || !mob.isAlive() || mob.removed) {
                it.remove();
                spirals.remove(entry.getKey());
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
        tickSpirals();
    }

    /**
     * Spirals are ticked on their own, not through a path state: combat code may stop the pet's
     * navigation (or it may have no route at all) and the quirk still has to play out.
     */
    private void tickSpirals() {
        if (spirals.isEmpty()) {
            return;
        }
        SuperCowConfig cfg = config();
        if (cfg == null) {
            spirals.clear();
            return;
        }
        long now = SuperCowMod.getServerTicksStatic();
        for (UUID uuid : new java.util.ArrayList<>(spirals.keySet())) {
            SkySpiral spiral = spirals.get(uuid);
            if (spiral == null) {
                continue;
            }
            Mob mob = spiral.state != null ? spiral.state.mob : null;
            if (mob == null || !mob.isAlive() || mob.removed) {
                spirals.remove(uuid);
                continue;
            }
            tickSkySpiral(mob, spiral, now, cfg, cfg.legacyMoveSpeed);
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

        // Skill: "转圈飞天". The original velocity mover had no vertical clamp worth the name and
        // could path itself into the sky; here it is a rare, self-terminating episode that owns
        // the movement for a couple of seconds and then hands control back to gravity.
        long now = SuperCowMod.getServerTicksStatic();
        if (spirals.containsKey(mob.getUUID())) {
            // The spiral is ticked independently (see tick()), so the mover must not fight it.
            return;
        }
        maybeStartSkySpiral(state, cfg, now);

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
        // Kept for combat only: while it is just walking (follow mode, no target) the pet should
        // walk a straight line like a normal animal instead of dancing in place.
        // Following the owner is not combat: the pet should walk, not dance.
        boolean inCombat = state.targetEntity != null
            && !(state.targetEntity instanceof net.minecraft.world.entity.player.Player);
        boolean strafing = !cfg.legacyStrafeOnlyInCombat || inCombat;
        double vx = flat.x;
        double vz = flat.z;
        if (strafing) {
            state.strafeAngle += RANDOM.nextDouble() < 0.1D ? (RANDOM.nextBoolean() ? 45.0D : -45.0D) : 10.0D;
            double rad = Math.toRadians(state.strafeAngle);
            double strafe = cfg.legacyStrafeStrength;
            vx += Math.cos(rad) * strafe;
            vz += Math.sin(rad) * strafe;
        } else {
            // Re-aim the path straight at the target every tick and cap the travel speed.
            Vec3 travel = state.targetPos.subtract(pos);
            Vec3 flatTravel = new Vec3(travel.x, 0.0D, travel.z);
            if (flatTravel.lengthSqr() > 1.0E-4D) {
                double walkSpeed = Math.min(cfg.legacyTravelSpeed, Math.max(0.12D, dist * 0.12D));
                flatTravel = flatTravel.normalize().scale(walkSpeed);
                vx = flatTravel.x;
                vz = flatTravel.z;
            }
        }

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

    // ------------------------------------------------------------------ "flew into the sky" quirk

    /**
     * Rolls the quirk once per newly locked target: the original mover had no meaningful vertical
     * clamp, so occasionally the pet simply aimed itself upwards and "pathfound" into the sky.
     * The roll happens per target (not per tick) so a long fight does not guarantee a launch.
     */
    private void maybeStartSkySpiral(PathState state, SuperCowConfig cfg, long now) {
        if (!cfg.legacyQuirksEnabled || !cfg.skySpiralEnabled || cfg.skySpiralChance <= 0.0D) {
            return;
        }
        Entity target = state.targetEntity;
        if (target == null) {
            return;
        }
        UUID targetUuid = target.getUUID();
        if (targetUuid.equals(state.spiralRolledTarget)) {
            return;
        }
        state.spiralRolledTarget = targetUuid;
        if (now - state.lastSpiralEndTick < cfg.skySpiralCooldownTicks) {
            return;
        }
        if (!Quirks.roll() || RANDOM.nextDouble() >= cfg.skySpiralChance) {
            return;
        }
        startSkySpiral(state.mob, state, now);
        SuperCowMod mod = SuperCowMod.getInstance();
        UUID ownerUuid = SuperCowMod.getOwnerUuidFromTags(state.mob);
        if (mod != null && ownerUuid != null) {
            net.minecraft.server.level.ServerPlayer owner = mod.getPlayer(ownerUuid);
            if (owner != null) {
                owner.sendMessage(new net.minecraft.network.chat.TextComponent(
                    "§d[超级牛] 糟了，它的寻路把自己送上天了……"), ownerUuid);
            }
        }
    }

    private void startSkySpiral(Mob mob, PathState state, long now) {
        SuperCowConfig cfg = config();
        if (mob == null || cfg == null) {
            return;
        }
        int duration = Math.max(10, cfg.skySpiralDurationTicks);
        spirals.put(mob.getUUID(), new SkySpiral(
            state,
            now,
            now + duration,
            mob.getY(),
            RANDOM.nextDouble() * Math.PI * 2.0D
        ));
        if (cfg.skySpiralLaunchesAway) {
            // One big kick at take-off so the pet visibly rockets off the ground instead of
            // slowly drifting upwards.
            mob.setDeltaMovement(mob.getDeltaMovement().x, cfg.skySpiralLaunchVelocity, mob.getDeltaMovement().z);
            mob.hasImpulse = true;
            if (mob.level instanceof net.minecraft.server.level.ServerLevel) {
                ((net.minecraft.server.level.ServerLevel) mob.level).sendParticles(
                    net.minecraft.core.particles.ParticleTypes.CLOUD,
                    mob.getX(), mob.getY() + 0.3D, mob.getZ(), 18, 0.5D, 0.2D, 0.5D, 0.05D);
            }
        }
    }

    private void tickSkySpiral(Mob mob, SkySpiral spiral, long now, SuperCowConfig cfg, double maxSpeed) {
        double maxHeight = Math.max(1.0D, cfg.skySpiralMaxHeight);
        double height = mob.getY() - spiral.anchorY;
        if (now >= spiral.endTick || height >= maxHeight) {
            endSkySpiral(spiral, now);
            return;
        }
        double angle = spiral.baseAngle + (now - spiral.startTick) * cfg.skySpiralSpinSpeed;
        double speed = maxSpeed * 1.1D;
        // 旱地起飞：前半段给满推力，接近上限才收力，视觉上就是"猛地往上冲"。
        double fade = Mth.clamp(1.0D - height / maxHeight, 0.15D, 1.0D);
        double lift = cfg.skySpiralLift * (height < maxHeight * 0.6D ? 1.0D : Math.max(fade, 0.35D));
        // 已经在下坠（比如撞了顶）就补一脚，保证整体是往上走的。
        if (mob.getDeltaMovement().y < 0.05D && height < maxHeight * 0.85D) {
            lift = Math.max(lift, cfg.skySpiralLift * 0.8D);
        }
        applyVelocity(mob, Math.cos(angle) * speed, Math.sin(angle) * speed, lift, maxSpeed);
        // Vanilla travel() eats the upward push of a walking mob: keep re-stamping the vertical
        // impulse so the launch actually climbs instead of hopping in place.
        if (height < maxHeight) {
            Vec3 dm = mob.getDeltaMovement();
            mob.setDeltaMovement(dm.x, Math.max(dm.y, lift), dm.z);
            mob.hasImpulse = true;
        }
    }

    private void endSkySpiral(SkySpiral spiral, long now) {
        Mob mob = spiral.state != null ? spiral.state.mob : null;
        if (spiral.state != null) {
            spiral.state.lastSpiralEndTick = now;
            spiral.state.stuckTicks = 0;
            if (mob != null) {
                spiral.state.lastPos = mob.position();
            }
        }
        if (mob != null) {
            spirals.remove(mob.getUUID());
            if (spiral.standalone) {
                // No chase target existed: drop the throw-away path state so the pet is not left
                // with a fake "stay still" route after landing.
                activePaths.remove(mob.getUUID());
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
            // Positive vertical motion (jump push, sky spiral lift) is kept but decays, so an
            // upward push can actually leave the ground without the pet hovering forever.
            double gravity = dy > 0.0D ? 0.05D : 0.08D;
            dy = Math.max(dy - gravity, -0.6D);
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
