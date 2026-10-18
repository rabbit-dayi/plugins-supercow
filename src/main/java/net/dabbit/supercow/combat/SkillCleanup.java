package net.dabbit.supercow.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.dabbit.supercow.SuperCowMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Central registry for every entity that a skill spawns.
 *
 * <p>The original plugin leaked entities in several skills (bomber chickens, exploding cows,
 * summoned helpers, music/firework tasks). Every skill-spawned entity is registered here with a
 * hard time-to-live, so it is removed even when its owning task object is lost, and an orphan
 * sweep removes any tagged entity that nobody tracks anymore.</p>
 */
public final class SkillCleanup {
    public static final String TAG_SKILL = "supercow_skill";
    private static final String TAG_KIND_PREFIX = "supercow_skill:";

    private static final Map<UUID, Tracked> TRACKED = new HashMap<>();

    private static final class Tracked {
        final Entity entity;
        final UUID ownerUuid;
        final String kind;
        final long expireTick;

        Tracked(Entity entity, UUID ownerUuid, String kind, long expireTick) {
            this.entity = entity;
            this.ownerUuid = ownerUuid;
            this.kind = kind;
            this.expireTick = expireTick;
        }
    }

    private SkillCleanup() {
    }

    public static void track(Entity entity, UUID ownerUuid, int ttlTicks, String kind) {
        if (entity == null) {
            return;
        }
        entity.addTag(TAG_SKILL);
        entity.addTag(TAG_KIND_PREFIX + kind);
        if (ownerUuid != null) {
            entity.addTag("supercow_owner:" + ownerUuid.toString());
        }
        long expire = SuperCowMod.getServerTicksStatic() + Math.max(20, ttlTicks);
        TRACKED.put(entity.getUUID(), new Tracked(entity, ownerUuid, kind, expire));
    }

    public static void untrack(Entity entity) {
        if (entity != null) {
            TRACKED.remove(entity.getUUID());
        }
    }

    public static boolean isTracked(Entity entity) {
        return entity != null && TRACKED.containsKey(entity.getUUID());
    }

    /** Called every server tick: expire and remove anything whose TTL ran out. */
    public static void tick() {
        long now = SuperCowMod.getServerTicksStatic();
        Iterator<Map.Entry<UUID, Tracked>> it = TRACKED.entrySet().iterator();
        while (it.hasNext()) {
            Tracked tracked = it.next().getValue();
            Entity entity = tracked.entity;
            if (entity == null || entity.removed) {
                it.remove();
                continue;
            }
            if (now >= tracked.expireTick) {
                despawn(entity);
                it.remove();
            }
        }
    }

    /**
     * Safety net: removes any entity carrying the skill tag that is no longer registered,
     * so a lost task object can never leave a chicken or a bomb cow behind.
     */
    public static void sweep(ServerLevel level) {
        if (level == null) {
            return;
        }
        List<Entity> orphans = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity == null || entity.removed) {
                continue;
            }
            if (entity.getTags().contains(TAG_SKILL) && !TRACKED.containsKey(entity.getUUID())) {
                orphans.add(entity);
            }
        }
        for (Entity orphan : orphans) {
            despawn(orphan);
        }
    }

    public static void cleanupForOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return;
        }
        Iterator<Map.Entry<UUID, Tracked>> it = TRACKED.entrySet().iterator();
        while (it.hasNext()) {
            Tracked tracked = it.next().getValue();
            if (ownerUuid.equals(tracked.ownerUuid)) {
                if (tracked.entity != null && !tracked.entity.removed) {
                    despawn(tracked.entity);
                }
                it.remove();
            }
        }
    }

    public static void cleanupKind(UUID ownerUuid, String kind) {
        Iterator<Map.Entry<UUID, Tracked>> it = TRACKED.entrySet().iterator();
        while (it.hasNext()) {
            Tracked tracked = it.next().getValue();
            if (tracked.ownerUuid != null && tracked.kind != null && tracked.kind.equals(kind)
                && (ownerUuid == null || ownerUuid.equals(tracked.ownerUuid))) {
                if (tracked.entity != null && !tracked.entity.removed) {
                    despawn(tracked.entity);
                }
                it.remove();
            }
        }
    }

    public static void cleanupAll() {
        for (Tracked tracked : TRACKED.values()) {
            if (tracked.entity != null && !tracked.entity.removed) {
                despawn(tracked.entity);
            }
        }
        TRACKED.clear();
    }

    public static int countAll() {
        return TRACKED.size();
    }

    public static int countForOwner(UUID ownerUuid) {
        int count = 0;
        for (Tracked tracked : TRACKED.values()) {
            if (ownerUuid != null && ownerUuid.equals(tracked.ownerUuid)) {
                count++;
            }
        }
        return count;
    }

    /** Human readable per-kind snapshot, used by /supercow skills and the self-check. */
    public static String describe() {
        Map<String, Integer> byKind = new HashMap<>();
        for (Tracked tracked : TRACKED.values()) {
            String key = tracked.kind == null ? "unknown" : tracked.kind;
            Integer current = byKind.get(key);
            byKind.put(key, current == null ? 1 : current + 1);
        }
        if (byKind.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : byKind.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    public static void despawn(Entity entity) {
        if (entity == null || entity.removed) {
            return;
        }
        if (entity.level instanceof ServerLevel) {
            ServerLevel level = (ServerLevel) entity.level;
            level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.3D, entity.getZ(), 4, 0.2D, 0.2D, 0.2D, 0.01D);
        }
        entity.remove();
    }
}
