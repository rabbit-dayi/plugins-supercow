package net.dabbit.supercow.combat;

import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.config.SuperCowConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The quirk skill library: the original ten, 41 second-wave and 30 third-wave skills.
 *
 * Every skill either finishes inside {@code quirkSkillDurationTicks} or spawns entities that are
 * registered in {@link SkillCleanup}, so nothing is left behind in the world (hard requirement).
 * Damage is deliberately low; these are flavour skills, not damage skills.
 *
 * Rules shared by the skill library:
 * <ul>
 *   <li>every spawned entity is tracked by SkillCleanup with the owner uuid,</li>
 *   <li>aoe picks victims through TargetValidator (never the owner, never a pet),</li>
 *   <li>damage goes through {@link #damage} so the kill is credited to the pet's owner,</li>
 *   <li>no Explosion / EvokerFangs: those break blocks and can hit the owner.</li>
 * </ul>
 */
public final class QuirkSkills {

    private static final List<Task> TASKS = new ArrayList<>();

    /** Fallback duration when a skill needs a shorter or longer life than the config value. */
    private static final int MIN_TICKS = 10;

    private static final Set<String> WAVE_ONE = setOf(
        "cow_rain", "milk_bomb", "moon_walk", "moo_wave", "hay_tornado",
        "butterfly_swarm", "zero_gravity", "mirror_cow", "teleport_swap", "confetti");

    private static final Set<String> WAVE_TWO = setOf(
        // 位移
        "moo_dash", "sapling_launch", "earth_dive", "wormhole",
        "grapple_rope", "tumble_roll", "spring_step", "meteor_jump",
        // 召唤
        "bee_swarm", "iron_calf", "wolf_pack", "snow_golem_turret",
        "fire_cracker_chain", "barn_totem", "phantom_wings",
        // 控制
        "mud_trap", "ice_field", "sleep_beam", "lasso_yank", "blizzard", "quicksand", "wind_gust",
        // 爆发
        "dairy_volcano", "cream_mine", "spore_bloom", "lightning_milk", "stampede", "buffalo_charge",
        // 增益
        "milk_shield", "cheese_power", "moo_roar", "pasture_heal", "sacred_bull_aura", "bull_aura",
        // 趣味
        "milk_rain_blessing", "cow_disguise", "moo_chorus", "herd_dance", "bell_toll", "milk_fountain",
        "piston_sheep_machine");

    private static final Set<String> WAVE_THREE = setOf(
        "anvil_guillotine", "sheep_rainbow_cannon", "tnt_hot_potato", "fishing_crane", "slime_pinball",
        "villager_auction", "end_rod_drill", "chicken_gatling", "boat_drift", "creeper_fakeout",
        "pufferfish_minefield", "bed_bounce_castle", "squid_ink_printer", "golem_chiropractor", "llama_spit_turret",
        "panda_bowling", "cat_keyboard_walker", "minecart_rollercoaster", "turtle_spinning_shell", "fox_berry_trap",
        "snowman_blender", "pig_rocket_express", "witch_cauldron_soup", "skeleton_jazz_band", "enderman_moving_company",
        "polar_bear_slap", "silverfish_tickle_pit", "dolphin_surfboard", "mooshroom_nuclear_stew", "bat_ufo_abduction");

    private QuirkSkills() {
    }

    public static boolean isQuirkSkill(String skillId) {
        if (skillId == null) {
            return false;
        }
        String id = skillId.toLowerCase();
        return WAVE_ONE.contains(id) || WAVE_TWO.contains(id) || WAVE_THREE.contains(id);
    }

    public static boolean cast(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, ServerPlayer owner, String skillId) {
        if (level == null || pet == null || ownerUuid == null || skillId == null) {
            return false;
        }
        SuperCowConfig cfg = SuperCowMod.getInstance().getConfig();
        int dur = Math.max(20, cfg.quirkSkillDurationTicks);
        try {
            // /supercow skill 允许在没有目标时释放：这里补一个“宠物附近最近的合法敌人”，
            // 免得所有依赖目标的技能都被静默吞掉。
            LivingEntity aim = target;
            if (aim == null || !aim.isAlive()) {
                aim = nearestTarget(level, pet.position(), 16.0D, ownerUuid, pet);
            }
            return dispatch(level, pet, ownerUuid, aim, rage, owner, skillId.toLowerCase(), cfg, dur);
        } catch (RuntimeException e) {
            // A quirk must never take the server thread down: log once and treat it as a no-op.
            warn("quirk skill " + skillId + " failed", e);
            return false;
        }
    }

    private static boolean dispatch(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, ServerPlayer owner,
                                    String id, SuperCowConfig cfg, int dur) {
        switch (id) {
            // ---- wave one ----
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
            // ---- wave two / 位移 ----
            case "moo_dash":
                return mooDash(level, pet, ownerUuid, target, rage, dur);
            case "sapling_launch":
                return saplingLaunch(level, pet, ownerUuid, target, dur);
            case "earth_dive":
                return earthDive(level, pet, ownerUuid, target, dur);
            case "wormhole":
                return wormhole(level, pet, ownerUuid, target, dur);
            case "grapple_rope":
                return grappleRope(level, pet, ownerUuid, target, dur);
            case "tumble_roll":
                return tumbleRoll(level, pet, ownerUuid, target, rage, dur);
            case "spring_step":
                return springStep(level, pet, ownerUuid, target, dur);
            case "meteor_jump":
                return meteorJump(level, pet, ownerUuid, target, dur);
            // ---- wave two / 召唤 ----
            case "bee_swarm":
                return beeSwarm(level, pet, ownerUuid, target, rage, dur);
            case "iron_calf":
                return ironCalf(level, pet, ownerUuid, target, dur);
            case "wolf_pack":
                return wolfPack(level, pet, ownerUuid, target, dur);
            case "snow_golem_turret":
                return snowGolemTurret(level, pet, ownerUuid, target, dur);
            case "fire_cracker_chain":
                return fireCrackerChain(level, pet, ownerUuid, target, dur);
            case "barn_totem":
                return barnTotem(level, pet, ownerUuid, target, dur);
            case "phantom_wings":
                return phantomWings(level, pet, ownerUuid, target, dur);
            // ---- wave two / 控制 ----
            case "mud_trap":
                return mudTrap(level, pet, ownerUuid, target, dur);
            case "ice_field":
                return iceField(level, pet, ownerUuid, target, dur);
            case "sleep_beam":
                return sleepBeam(level, pet, ownerUuid, target, dur);
            case "lasso_yank":
                return lassoYank(level, pet, ownerUuid, target, dur);
            case "blizzard":
                return blizzard(level, pet, ownerUuid, target, dur);
            case "quicksand":
                return quicksand(level, pet, ownerUuid, target, dur);
            case "wind_gust":
                return windGust(level, pet, ownerUuid, target, rage, dur);
            // ---- wave two / 爆发 ----
            case "dairy_volcano":
                return dairyVolcano(level, pet, ownerUuid, target, rage, dur);
            case "cream_mine":
                return creamMine(level, pet, ownerUuid, target, dur);
            case "spore_bloom":
                return sporeBloom(level, pet, ownerUuid, target, dur);
            case "lightning_milk":
                return lightningMilk(level, pet, ownerUuid, target);
            case "stampede":
                return stampede(level, pet, ownerUuid, target, rage, dur);
            case "buffalo_charge":
                return buffaloCharge(level, pet, ownerUuid, target, rage, dur);
            // ---- wave two / 增益 ----
            case "milk_shield":
                return milkShield(level, pet, ownerUuid, target, rage, dur);
            case "cheese_power":
                return cheesePower(level, pet, ownerUuid, target, rage, dur);
            case "moo_roar":
                return mooRoar(level, pet, ownerUuid, target, rage, dur);
            case "pasture_heal":
                return pastureHeal(level, pet, ownerUuid, target, rage, dur);
            case "sacred_bull_aura":
                return sacredBullAura(level, pet, ownerUuid, target, rage, dur);
            case "bull_aura":
                return bullAura(level, pet, ownerUuid, target, rage, dur);
            // ---- wave two / 趣味 ----
            case "milk_rain_blessing":
                return milkRainBlessing(level, pet, ownerUuid, target, rage, dur);
            case "cow_disguise":
                return cowDisguise(level, pet, ownerUuid, target, dur);
            case "moo_chorus":
                return mooChorus(level, pet, ownerUuid, target, rage, dur);
            case "herd_dance":
                return herdDance(level, pet, ownerUuid, target, rage, dur);
            case "bell_toll":
                return bellToll(level, pet, ownerUuid, target, rage, dur);
            case "milk_fountain":
                return milkFountain(level, pet, ownerUuid, target, rage, dur);
            case "piston_sheep_machine":
                return pistonSheepMachine(level, pet, ownerUuid, target, rage, dur);

            // ---- wave three (30 abstract / contraption skills) ----
            case "anvil_guillotine":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "sheep_rainbow_cannon":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "tnt_hot_potato":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "fishing_crane":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "slime_pinball":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "villager_auction":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "end_rod_drill":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "chicken_gatling":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "boat_drift":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "creeper_fakeout":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "pufferfish_minefield":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "bed_bounce_castle":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "squid_ink_printer":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "golem_chiropractor":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "llama_spit_turret":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "panda_bowling":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "cat_keyboard_walker":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "minecart_rollercoaster":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "turtle_spinning_shell":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "fox_berry_trap":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "snowman_blender":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "pig_rocket_express":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "witch_cauldron_soup":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "skeleton_jazz_band":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "enderman_moving_company":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "polar_bear_slap":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "silverfish_tickle_pit":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "dolphin_surfboard":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "mooshroom_nuclear_stew":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            case "bat_ufo_abduction":
                return contraption(level, pet, ownerUuid, target, rage, dur, id);
            default:
                return false;
        }
    }

    // ================================================================== wave one (unchanged behaviour)

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
                    for (LivingEntity victim : nearby(level, cow.getX(), cow.getY(), cow.getZ(), 2.0D, ownerUuid, pet, target)) {
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
        final LivingEntity victim0 = target;
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
                for (LivingEntity victim : nearby(level, to.x, to.y, to.z, radius, ownerUuid, pet, victim0)) {
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
                        pet.playSound(SoundEvents.COW_AMBIENT, 1.2F, 0.6F);
                    }
                    for (LivingEntity victim : nearby(level, pet.getX(), pet.getY(), pet.getZ(), radius, ownerUuid, pet, target)) {
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
                long age = now - startTick;
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
                if (age % 20 == 0 && target.isAlive()) {
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
        pet.playSound(SoundEvents.FIREWORK_ROCKET_BLAST, 1.4F, 1.1F);
        for (LivingEntity victim : nearby(level, at.x, at.y, at.z, radius, ownerUuid, pet, target)) {
            damage(victim, (float) cfg.confettiDamage, pet, ownerUuid);
            victim.push(0.0D, 0.18D, 0.0D);
        }
        return true;
    }

    // ================================================================== wave two: 位移

    /** 蛮牛冲撞：低头冲过去，沿途撞飞目标。 */
    private static boolean mooDash(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 dir = target != null ? horizontal(pet.position(), target.position()).normalize() : lookDirection(pet);
        final int ticks = rage ? 18 : 14;
        final Set<UUID> hit = new HashSet<>();
        pet.playSound(SoundEvents.COW_AMBIENT, 1.3F, 0.7F);
        TASKS.add(new Task(level, ownerUuid, ticks) {
            @Override
            boolean tick(long now) {
                if (!pet.isAlive()) {
                    return false;
                }
                Vec3 v = pet.getDeltaMovement();
                pet.setDeltaMovement(dir.x * 0.62D, v.y, dir.z * 0.62D);
                pet.hurtMarked = true;
                level.sendParticles(ParticleTypes.CLOUD, pet.getX(), pet.getY() + 0.3D, pet.getZ(), 3, 0.25D, 0.1D, 0.25D, 0.01D);
                for (LivingEntity victim : nearby(level, pet.getX(), pet.getY(), pet.getZ(), 1.7D, ownerUuid, pet, target)) {
                    if (hit.add(victim.getUUID())) {
                        damage(victim, rage ? 3.0F : 2.0F, pet, ownerUuid);
                        level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + 0.8D, victim.getZ(), 8, 0.3D, 0.3D, 0.3D, 0.1D);
                    }
                    victim.push(dir.x * 0.55D, 0.22D, dir.z * 0.55D);
                }
                return true;
            }
        });
        return true;
    }

    /** 树苗弹射：脚下长出一棵树苗把宠物弹上天。 */
    private static boolean saplingLaunch(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 at = pet.position();
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 0.2D, at.z, 20, 0.5D, 0.2D, 0.5D, 0.05D);
        level.playSound(null, pet.getX(), pet.getY(), pet.getZ(), SoundEvents.GRASS_BREAK, net.minecraft.sounds.SoundSource.NEUTRAL, 1.2F, 1.4F);
        Vec3 v = pet.getDeltaMovement();
        pet.setDeltaMovement(v.x, 1.05D, v.z);
        pet.hurtMarked = true;
        TASKS.add(new Task(level, ownerUuid, 40) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (!pet.isAlive()) {
                    return false;
                }
                if (age % 2 == 0) {
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pet.getX(), pet.getY(), pet.getZ(), 2, 0.3D, 0.1D, 0.3D, 0.0D);
                }
                if (age == 20 && target != null && target.isAlive()) {
                    // 落地前扑向目标，砸一下
                    Vec3 aim = horizontal(pet.position(), target.position()).normalize().scale(0.45D);
                    pet.setDeltaMovement(aim.x, -0.15D, aim.z);
                }
                return true;
            }
        });
        return true;
    }

    /** 遁地冲锋：钻进地面，再从目标脚下钻出来把它抛起。 */
    private static boolean earthDive(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 end = target != null ? target.position() : pet.position().add(lookDirection(pet).scale(6.0D));
        final Vec3 start = pet.position();
        pet.playSound(SoundEvents.GRAVEL_BREAK, 1.2F, 0.7F);
        level.sendParticles(ParticleTypes.POOF, start.x, start.y + 0.2D, start.z, 20, 0.5D, 0.2D, 0.5D, 0.05D);
        TASKS.add(new Task(level, ownerUuid, 20) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (!pet.isAlive()) {
                    return false;
                }
                if (age < 10) {
                    double t = age / 10.0D;
                    double x = start.x + (end.x - start.x) * t;
                    double z = start.z + (end.z - start.z) * t;
                    pet.teleportTo(x, start.y, z);
                    level.sendParticles(ParticleTypes.POOF, x, start.y + 0.1D, z, 6, 0.35D, 0.05D, 0.35D, 0.02D);
                    return true;
                }
                if (age == 10) {
                    pet.teleportTo(end.x, end.y, end.z);
                    level.sendParticles(ParticleTypes.EXPLOSION, end.x, end.y + 0.5D, end.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                    level.sendParticles(ParticleTypes.POOF, end.x, end.y + 0.3D, end.z, 25, 0.6D, 0.4D, 0.6D, 0.08D);
                    pet.playSound(SoundEvents.ANVIL_LAND, 0.9F, 1.2F);
                    for (LivingEntity victim : nearby(level, end.x, end.y, end.z, 2.6D, ownerUuid, pet, target)) {
                        damage(victim, 2.5F, pet, ownerUuid);
                        victim.push(0.0D, 0.55D, 0.0D);
                    }
                }
                return age < 16;
            }
        });
        return true;
    }

    /** 虫洞：原地开一个洞，宠物瞬间钻到前面约 10 格，出口冒烟。 */
    private static boolean wormhole(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 from = pet.position();
        // 出口优先选“目标身后 1.5 格”，没有目标就沿视线前方 10 格
        Vec3 to;
        if (target != null && target.isAlive() && target != pet && horizontal(from, target.position()).lengthSqr() > 1.0E-4D) {
            Vec3 aim = horizontal(from, target.position()).normalize();
            to = target.position().subtract(aim.scale(1.5D));
        } else {
            to = from.add(lookDirection(pet).scale(10.0D));
        }
        // 出口底下必须是实心地面，否则退回宠物原来那一层高度
        if (level.getBlockState(new BlockPos(to.x, to.y - 0.2D, to.z)).isAir()) {
            to = new Vec3(to.x, from.y, to.z);
        }
        final Vec3 exit = to;
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.0D, from.z, 40, 0.4D, 0.8D, 0.4D, 0.08D);
        pet.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.8F);
        pet.teleportTo(exit.x, exit.y, exit.z);
        level.sendParticles(ParticleTypes.PORTAL, exit.x, exit.y + 1.0D, exit.z, 40, 0.4D, 0.8D, 0.4D, 0.1D);
        pet.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.2F, 1.3F);
        // 出口处顺手震一下，伤害很低
        for (LivingEntity victim : nearby(level, exit.x, exit.y, exit.z, 2.2D, ownerUuid, pet, target)) {
            damage(victim, 1.0F, pet, ownerUuid);
        }
        TASKS.add(new Task(level, ownerUuid, 20) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.0D, from.z, 4, 0.3D, 0.5D, 0.3D, 0.01D);
                level.sendParticles(ParticleTypes.PORTAL, exit.x, exit.y + 1.0D, exit.z, 2, 0.3D, 0.5D, 0.3D, 0.01D);
                return age < 20;
            }
        });
        return true;
    }

    /** 套索飞爪：甩出套索，把自己拉向目标。 */
    private static boolean grappleRope(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        if (target == null) {
            return false;
        }
        final LivingEntity anchor = target;
        pet.playSound(SoundEvents.FISHING_BOBBER_THROW, 1.0F, 1.2F);
        TASKS.add(new Task(level, ownerUuid, 30) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (!pet.isAlive()) {
                    return false;
                }
                Vec3 from = pet.position().add(0.0D, 1.0D, 0.0D);
                Vec3 to = anchor.position().add(0.0D, 1.0D, 0.0D);
                for (int i = 0; i <= 6; i++) {
                    double t = i / 6.0D;
                    level.sendParticles(ParticleTypes.POOF, from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
                Vec3 aim = horizontal(pet.position(), anchor.position());
                double dist = aim.length();
                if (dist > 1.6D) {
                    aim = aim.normalize().scale(0.72D);
                    pet.setDeltaMovement(aim.x, Math.max(pet.getDeltaMovement().y, 0.22D), aim.z);
                    pet.hurtMarked = true;
                } else {
                    pet.setDeltaMovement(pet.getDeltaMovement().x * 0.3D, pet.getDeltaMovement().y, pet.getDeltaMovement().z * 0.3D);
                    damage(anchor, 2.0F, pet, ownerUuid);
                    pet.playSound(SoundEvents.FISHING_BOBBER_RETRIEVE, 1.0F, 1.2F);
                    return false;
                }
                return true;
            }
        });
        return true;
    }

    /** 翻滚：向侧面滚出去，顺便拍开路上的怪。 */
    private static boolean tumbleRoll(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        Vec3 forward = lookDirection(pet);
        final Vec3 side = new Vec3(-forward.z, 0.0D, forward.x).normalize();
        final int ticks = 12;
        pet.playSound(SoundEvents.SLIME_JUMP, 1.0F, 1.3F);
        TASKS.add(new Task(level, ownerUuid, ticks) {
            @Override
            boolean tick(long now) {
                if (!pet.isAlive()) {
                    return false;
                }
                Vec3 v = pet.getDeltaMovement();
                pet.setDeltaMovement(side.x * 0.55D, Math.max(v.y, 0.12D), side.z * 0.55D);
                pet.hurtMarked = true;
                level.sendParticles(ParticleTypes.CLOUD, pet.getX(), pet.getY() + 0.3D, pet.getZ(), 3, 0.2D, 0.1D, 0.2D, 0.01D);
                for (LivingEntity victim : nearby(level, pet.getX(), pet.getY(), pet.getZ(), 1.7D, ownerUuid, pet, target)) {
                    damage(victim, rage ? 2.0F : 1.2F, pet, ownerUuid);
                    victim.push(side.x * 0.6D, 0.2D, side.z * 0.6D);
                }
                return true;
            }
        });
        return true;
    }

    /** 弹簧步：在上面弹跳，落地就踩一下周围。 */
    private static boolean springStep(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        TASKS.add(new Task(level, ownerUuid, 40) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (!pet.isAlive()) {
                    return false;
                }
                boolean bounced = pet.getDeltaMovement().y > 0.25D;
                if (age % 4 == 0) {
                    level.sendParticles(ParticleTypes.ITEM_SLIME, pet.getX(), pet.getY() + 0.1D, pet.getZ(), 3, 0.35D, 0.05D, 0.35D, 0.02D);
                }
                if (pet.isOnGround() && !bounced) {
                    Vec3 v = pet.getDeltaMovement();
                    pet.setDeltaMovement(v.x, 0.72D, v.z);
                    pet.hurtMarked = true;
                    pet.playSound(SoundEvents.SLIME_SQUISH_SMALL, 1.0F, 1.3F);
                    for (LivingEntity victim : nearby(level, pet.getX(), pet.getY(), pet.getZ(), 2.2D, ownerUuid, pet, target)) {
                        damage(victim, 1.2F, pet, ownerUuid);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 陨石跳：跳起来，然后一头砸向目标头顶。 */
    private static boolean meteorJump(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 aim = target != null ? target.position() : pet.position().add(lookDirection(pet).scale(6.0D));
        pet.playSound(SoundEvents.BLAZE_SHOOT, 1.0F, 1.2F);
        TASKS.add(new Task(level, ownerUuid, 26) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (!pet.isAlive()) {
                    return false;
                }
                if (age < 10) {
                    Vec3 v = pet.getDeltaMovement();
                    pet.setDeltaMovement(v.x, 0.42D, v.z);
                    pet.hurtMarked = true;
                    level.sendParticles(ParticleTypes.FLAME, pet.getX(), pet.getY(), pet.getZ(), 3, 0.25D, 0.25D, 0.25D, 0.02D);
                    return true;
                }
                Vec3 aim2 = pet.position().add(horizontal(pet.position(), aim).normalize().scale(0.42D));
                pet.teleportTo(aim2.x, pet.getY(), aim2.z);
                level.sendParticles(ParticleTypes.LAVA, pet.getX(), pet.getY(), pet.getZ(), 2, 0.2D, 0.2D, 0.2D, 0.0D);
                if (age == 16) {
                    level.sendParticles(ParticleTypes.EXPLOSION, pet.getX(), pet.getY(), pet.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
                    pet.playSound(SoundEvents.GENERIC_EXPLODE, 0.8F, 1.4F);
                    for (LivingEntity victim : nearby(level, pet.getX(), pet.getY(), pet.getZ(), 2.6D, ownerUuid, pet, target)) {
                        damage(victim, 2.4F, pet, ownerUuid);
                        victim.push(0.0D, 0.35D, 0.0D);
                    }
                }
                return age < 20;
            }
        });
        return true;
    }

    // ================================================================== wave two: 召唤
    /** 蜂群：放出一群蜜蜂追着目标蜇。 */
    private static boolean beeSwarm(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        if (target == null) {
            return false;
        }
        final LivingEntity foe = target;
        int count = rage ? 4 : 3;
        final List<UUID> bees = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Bee bee = EntityType.BEE.create(level);
            if (bee == null) {
                continue;
            }
            double angle = i * (Math.PI * 2.0D / count);
            bee.moveTo(foe.getX() + Math.cos(angle) * 2.2D, foe.getY() + 1.5D, foe.getZ() + Math.sin(angle) * 2.2D, 0.0F, 0.0F);
            bee.addTag("supercow_bee");
            bee.setNoAi(true);
            bee.setInvulnerable(true);
            bee.setSilent(true);
            if (!level.addFreshEntity(bee)) {
                bee.remove();
                continue;
            }
            SkillCleanup.track(bee, ownerUuid, dur + 40, "bee_swarm");
            bees.add(bee.getUUID());
        }
        if (bees.isEmpty()) {
            return false;
        }
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                for (int i = 0; i < bees.size(); i++) {
                    Entity entity = level.getEntity(bees.get(i));
                    if (!(entity instanceof Mob) || !entity.isAlive()) {
                        continue;
                    }
                    Mob bee = (Mob) entity;
                    double angle = age * 0.45D + i * (Math.PI * 2.0D / Math.max(1, bees.size()));
                    double r = 1.6D + Math.sin(age * 0.2D + i) * 0.5D;
                    if (foe.isAlive()) {
                        bee.moveTo(foe.getX() + Math.cos(angle) * r, foe.getY() + 1.2D + Math.sin(angle * 1.6D) * 0.5D,
                            foe.getZ() + Math.sin(angle) * r, pet.yRot, 0.0F);
                    }
                    if (age % 10 == 0) {
                        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, bee.getX(), bee.getY() + 0.5D, bee.getZ(), 1, 0.1D, 0.1D, 0.1D, 0.0D);
                    }
                }
                if (age == 1) {
                    pet.playSound(SoundEvents.BEE_LOOP, 1.0F, 1.4F);
                }
                if (age % 20 == 0 && foe.isAlive()) {
                    damage(foe, 1.5F, pet, ownerUuid);
                    foe.addEffect(new MobEffectInstance(MobEffects.POISON, 40, 0));
                    pet.playSound(SoundEvents.BEE_STING, 0.6F, 1.5F);
                }
                return true;
            }
        });
        return true;
    }

    /** 铁犊子：立一尊铁傀儡挡在前面替宠物吃伤害。 */
    private static boolean ironCalf(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        Mob golem = EntityType.IRON_GOLEM.create(level);
        if (golem == null) {
            return false;
        }
        Vec3 spawn = pet.position().add(lookDirection(pet).scale(1.8D));
        golem.moveTo(spawn.x, spawn.y, spawn.z, pet.yRot, 0.0F);
        golem.addTag("supercow_iron_calf");
        golem.setInvulnerable(true);
        golem.setSilent(true);
        golem.setPersistenceRequired();
        if (!level.addFreshEntity(golem)) {
            golem.remove();
            return false;
        }
        SkillCleanup.track(golem, ownerUuid, dur + 20, "iron_calf");
        pet.playSound(SoundEvents.IRON_GOLEM_STEP, 1.0F, 1.0F);
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                if (golem.removed || !golem.isAlive()) {
                    return false;
                }
                LivingEntity foe = golem.getTarget();
                if (foe == null || !foe.isAlive()) {
                    LivingEntity picked = target != null && target.isAlive() ? target : nearestTarget(level, golem.position(), 10.0D, ownerUuid, pet);
                    if (picked != null) {
                        golem.setTarget(picked);
                        golem.getNavigation().moveTo(picked, 1.0D);
                    }
                } else if (now % 10 == 0) {
                    golem.getNavigation().moveTo(foe, 1.0D);
                }
                if (now % 5 == 0) {
                    level.sendParticles(ParticleTypes.ANGRY_VILLAGER, golem.getX(), golem.getY() + 2.0D, golem.getZ(), 1, 0.2D, 0.2D, 0.2D, 0.0D);
                }
                // 铁傀儡的普攻走原版伤害源，这里额外补一刀“技能伤害”，保证归到主人名下
                LivingEntity swing = golem.getTarget();
                if (swing != null && swing.isAlive() && golem.distanceToSqr(swing) < 9.0D && now % 20 == 0) {
                    damage(swing, 2.0F, pet, ownerUuid);
                    swing.push(horizontal(golem.position(), swing.position()).normalize().x * 0.4D, 0.2D,
                        horizontal(golem.position(), swing.position()).normalize().z * 0.4D);
                    level.sendParticles(ParticleTypes.CRIT, swing.getX(), swing.getY() + 1.0D, swing.getZ(), 8, 0.3D, 0.3D, 0.3D, 0.1D);
                }
                return true;
            }
        });
        return true;
    }

    /** 狼群：三只狼包抄目标，咬一口就走。 */
    private static boolean wolfPack(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        if (target == null) {
            return false;
        }
        final LivingEntity foe = target;
        final List<UUID> wolves = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Wolf wolf = EntityType.WOLF.create(level);
            if (wolf == null) {
                continue;
            }
            double angle = pet.yRot * Math.PI / 180.0D + i * (Math.PI * 2.0D / 3.0D);
            wolf.moveTo(pet.getX() + Math.cos(angle) * 2.0D, pet.getY(), pet.getZ() + Math.sin(angle) * 2.0D, 0.0F, 0.0F);
            wolf.addTag("supercow_wolf");
            wolf.setTame(true);
            wolf.setCustomNameVisible(false);
            wolf.setSilent(true);
            wolf.setPersistenceRequired();
            if (!level.addFreshEntity(wolf)) {
                wolf.remove();
                continue;
            }
            SkillCleanup.track(wolf, ownerUuid, dur + 20, "wolf_pack");
            wolves.add(wolf.getUUID());
        }
        if (wolves.isEmpty()) {
            return false;
        }
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age == 1) {
                    pet.playSound(SoundEvents.WOLF_HOWL, 1.0F, 1.0F);
                }
                LivingEntity prey = foe.isAlive() ? foe : nearestTarget(level, pet.position(), 16.0D, ownerUuid, pet);
                for (int i = 0; i < wolves.size(); i++) {
                    Entity entity = level.getEntity(wolves.get(i));
                    if (!(entity instanceof Mob) || !entity.isAlive()) {
                        continue;
                    }
                    Mob wolf = (Mob) entity;
                    if (prey != null && prey.isAlive()) {
                        if (age % 10 == 0) {
                            wolf.setTarget(prey);
                            wolf.getNavigation().moveTo(prey, 1.15D);
                        }
                        wolf.getLookControl().setLookAt(prey, 30.0F, 30.0F);
                        if (age % 15 == 0 && wolf.distanceToSqr(prey) < 6.0D) {
                            damage(prey, 1.5F, pet, ownerUuid);
                            level.sendParticles(ParticleTypes.CRIT, prey.getX(), prey.getY() + 0.6D, prey.getZ(), 8, 0.3D, 0.3D, 0.3D, 0.1D);
                        }
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 雪人炮台：雪人立在宠物头顶扔雪球。 */
    private static boolean snowGolemTurret(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        SnowGolem golem = EntityType.SNOW_GOLEM.create(level);
        if (golem == null) {
            return false;
        }
        golem.moveTo(pet.getX(), pet.getY() + 1.9D, pet.getZ(), 0.0F, 0.0F);
        golem.addTag("supercow_snow_golem");
        golem.setNoAi(true);
        golem.setInvulnerable(true);
        golem.setSilent(true);
        if (!level.addFreshEntity(golem)) {
            golem.remove();
            return false;
        }
        SkillCleanup.track(golem, ownerUuid, dur + 20, "snow_golem_turret");
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                if (golem.removed || !golem.isAlive()) {
                    return false;
                }
                long age = now - startTick;
                golem.moveTo(pet.getX(), pet.getY() + 1.9D, pet.getZ(), pet.yRot, 0.0F);
                LivingEntity foe = target != null && target.isAlive() ? target : nearestTarget(level, golem.position(), 12.0D, ownerUuid, pet);
                if (foe == null) {
                    return true;
                }
                golem.getLookControl().setLookAt(foe, 40.0F, 40.0F);
                if (age % 8 == 0) {
                    shootSmall(level, golem, foe, EntityType.SNOWBALL, SoundEvents.SNOW_GOLEM_SHOOT, "snow_golem_turret", ownerUuid, 60);
                }
                return true;
            }
        });
        return true;
    }

    /** 鞭炮串：一串鞭炮从宠物身上甩出去，一个接一个炸。 */
    private static boolean fireCrackerChain(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 end = target != null ? target.position() : pet.position().add(lookDirection(pet).scale(4.0D));
        pet.playSound(SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.0F, 1.1F);
        TASKS.add(new Task(level, ownerUuid, 36) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                for (int i = 0; i < 8; i++) {
                    double t = i / 7.0D;
                    double x = pet.getX() + (end.x - pet.getX()) * t;
                    double y = pet.getY() + 0.8D + (end.y - pet.getY()) * t;
                    double z = pet.getZ() + (end.z - pet.getZ()) * t;
                    if (age >= i * 4 && age < i * 4 + 8) {
                        level.sendParticles(ParticleTypes.FLAME, x, y, z, 3, 0.15D, 0.15D, 0.15D, 0.01D);
                    }
                    if (age == i * 4) {
                        level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                        level.sendParticles(ParticleTypes.FIREWORK, x, y, z, 12, 0.3D, 0.3D, 0.3D, 0.08D);
                        pet.playSound(SoundEvents.FIREWORK_ROCKET_BLAST, 0.9F, 1.6F);
                        for (LivingEntity victim : nearby(level, x, y, z, 2.0D, ownerUuid, pet, target)) {
                            damage(victim, 1.2F, pet, ownerUuid);
                        }
                    }
                }
                return age < 36;
            }
        });
        return true;
    }

    /** 仓鼠图腾：插一根木桩，木桩持续往怪物方向射箭。 */
    private static boolean barnTotem(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 at = pet.position().add(lookDirection(pet).scale(1.4D));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 0.5D, at.z, 20, 0.3D, 0.6D, 0.3D, 0.05D);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.2F);
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 3 == 0) {
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 0.2D + (age % 12) * 0.15D, at.z, 2, 0.1D, 0.1D, 0.1D, 0.0D);
                }
                if (age % 10 == 0) {
                    LivingEntity foe = target != null && target.isAlive() ? target : nearestTarget(level, at, 14.0D, ownerUuid, pet);
                    if (foe != null) {
                        Arrow arrow = new Arrow(level, at.x, at.y + 1.4D, at.z);
                        arrow.setOwner(pet);
                        Vec3 aim = foe.getEyePosition(1.0F).subtract(at.add(0.0D, 1.4D, 0.0D));
                        aim = aim.lengthSqr() < 0.001D ? new Vec3(0.0D, 1.0D, 0.0D) : aim.normalize();
                        arrow.shoot(aim.x, aim.y, aim.z, 1.6F, 1.0F);
                        arrow.addTag("supercow_totem_arrow");
                        if (level.addFreshEntity(arrow)) {
                            SkillCleanup.track(arrow, ownerUuid, 60, "totem_arrow");
                            pet.playSound(SoundEvents.ARROW_SHOOT, 0.6F, 1.2F);
                        } else {
                            arrow.remove();
                        }
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 幻翼之翼：一只幻翼在头顶盘旋，定期冲下来咬一口。 */
    private static boolean phantomWings(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        Mob phantom = EntityType.PHANTOM.create(level);
        if (phantom == null) {
            return false;
        }
        phantom.moveTo(pet.getX(), pet.getY() + 4.0D, pet.getZ(), 0.0F, 0.0F);
        phantom.addTag("supercow_phantom");
        phantom.setNoAi(true);
        phantom.setInvulnerable(true);
        phantom.setSilent(true);
        if (!level.addFreshEntity(phantom)) {
            phantom.remove();
            return false;
        }
        SkillCleanup.track(phantom, ownerUuid, dur + 20, "phantom_wings");
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                if (phantom.removed || !phantom.isAlive()) {
                    return false;
                }
                long age = now - startTick;
                double angle = age * 0.18D;
                phantom.moveTo(pet.getX() + Math.cos(angle) * 3.0D, pet.getY() + 3.0D + Math.sin(angle * 2.0D) * 0.5D,
                    pet.getZ() + Math.sin(angle) * 3.0D, pet.yRot, 0.0F);
                if (age % 10 == 5) {
                    LivingEntity foe = target != null && target.isAlive() ? target : nearestTarget(level, phantom.position(), 10.0D, ownerUuid, pet);
                    if (foe != null) {
                        level.sendParticles(ParticleTypes.SWEEP_ATTACK, foe.getX(), foe.getY() + 0.8D, foe.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
                        damage(foe, 2.0F, pet, ownerUuid);
                        pet.playSound(SoundEvents.PHANTOM_BITE, 0.8F, 1.0F);
                    }
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== wave two: 控制
    /** 泥潭：脚下一圈泥，踩进去的怪打滑 + 缓慢。 */
    private static boolean mudTrap(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 center = target != null ? target.position() : pet.position();
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 4 == 0) {
                    for (int i = 0; i < 10; i++) {
                        double angle = i * (Math.PI * 2.0D / 10.0D) + age * 0.05D;
                        level.sendParticles(ParticleTypes.POOF, center.x + Math.cos(angle) * 2.6D, center.y + 0.1D, center.z + Math.sin(angle) * 2.6D,
                            1, 0.1D, 0.02D, 0.1D, 0.0D);
                    }
                }
                if (age % 10 == 0) {
                    for (LivingEntity victim : nearby(level, center.x, center.y, center.z, 3.2D, ownerUuid, pet, target)) {
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1));
                        damage(victim, 0.6F, pet, ownerUuid);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 冰封领域：地上铺一层冰，踩上去的怪滑得站不稳。 */
    private static boolean iceField(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 center = pet.position();
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 3 == 0) {
                    for (int i = 0; i < 14; i++) {
                        double angle = i * (Math.PI * 2.0D / 14.0D);
                        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, center.x + Math.cos(angle) * 3.0D, center.y + 0.1D, center.z + Math.sin(angle) * 3.0D,
                            1, 0.15D, 0.02D, 0.15D, 0.0D);
                    }
                }
                for (LivingEntity victim : nearby(level, center.x, center.y, center.z, 3.5D, ownerUuid, pet, target)) {
                    if (!victim.isOnGround()) {
                        continue;
                    }
                    Vec3 v = victim.getDeltaMovement();
                    double sx = (level.random.nextDouble() - 0.5D) * 0.5D;
                    double sz = (level.random.nextDouble() - 0.5D) * 0.5D;
                    victim.setDeltaMovement(v.x * 0.7D + sx, v.y, v.z * 0.7D + sz);
                    victim.hurtMarked = true;
                    if (age % 20 == 0) {
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 0));
                        damage(victim, 0.5F, pet, ownerUuid);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 催眠光波：一道光扫过去，打中的怪原地发呆并持续掉血。 */
    private static boolean sleepBeam(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        if (target == null) {
            return false;
        }
        final LivingEntity foe = target;
        final Vec3 from = pet.position().add(0.0D, 1.0D, 0.0D);
        final Vec3 to = foe.getEyePosition(1.0F);
        pet.playSound(SoundEvents.ENDERMAN_STARE, 1.0F, 1.6F);
        TASKS.add(new Task(level, ownerUuid, 20) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age > 10) {
                    return false;
                }
                for (int i = 0; i <= 10; i++) {
                    double t = i / 10.0D;
                    level.sendParticles(ParticleTypes.END_ROD, from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t,
                        1, 0.03D, 0.03D, 0.03D, 0.0D);
                }
                if (age == 8) {
                    for (LivingEntity victim : nearby(level, foe.getX(), foe.getY(), foe.getZ(), 2.5D, ownerUuid, pet, foe)) {
                        victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 1));
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 2));
                        victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
                        victim.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0));
                        damage(victim, 0.5F, pet, ownerUuid);
                        level.sendParticles(ParticleTypes.WITCH, victim.getX(), victim.getY() + 1.0D, victim.getZ(), 15, 0.4D, 0.6D, 0.4D, 0.02D);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 缰绳拖拽：甩出套索套住目标，一点点往宠物这边拽。 */
    private static boolean lassoYank(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        if (target == null || target == pet || target.level != pet.level) {
            return false;
        }
        final LivingEntity victimRef = target;
        pet.playSound(SoundEvents.FISHING_BOBBER_THROW, 1.0F, 1.0F);
        TASKS.add(new Task(level, ownerUuid, 24) {
            @Override
            boolean tick(long now) {
                if (!pet.isAlive() || !victimRef.isAlive()) {
                    return false;
                }
                Vec3 pull = horizontal(victimRef.position(), pet.position());
                double dist = pull.length();
                if (dist > 1.0E-4D) {
                    pull = pull.normalize().scale(0.35D);
                    victimRef.push(pull.x, 0.05D, pull.z);
                    victimRef.hurtMarked = true;
                }
                Vec3 a = pet.position().add(0.0D, 1.0D, 0.0D);
                Vec3 b = victimRef.getEyePosition(1.0F);
                for (int i = 0; i <= 6; i++) {
                    double t = i / 6.0D;
                    level.sendParticles(ParticleTypes.POOF, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t,
                        1, 0.02D, 0.02D, 0.02D, 0.0D);
                }
                if (now - startTick == 20) {
                    damage(victimRef, 1.5F, pet, ownerUuid);
                    victimRef.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
                }
                return true;
            }
        });
        return true;
    }

    /** 暴风雪：风雪糊住一片区域，里面又慢又看不见。 */
    private static boolean blizzard(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 center = target != null ? target.position() : pet.position();
        pet.playSound(SoundEvents.WEATHER_RAIN, 1.0F, 0.5F);
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 2 == 0) {
                    for (int i = 0; i < 6; i++) {
                        double angle = level.random.nextDouble() * Math.PI * 2.0D;
                        double r = level.random.nextDouble() * 4.5D;
                        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, center.x + Math.cos(angle) * r, center.y + 2.5D - level.random.nextDouble() * 1.5D,
                            center.z + Math.sin(angle) * r, 2, 0.25D, 0.25D, 0.25D, 0.05D);
                    }
                }
                if (age % 10 == 0) {
                    for (LivingEntity victim : nearby(level, center.x, center.y, center.z, 4.5D, ownerUuid, pet, target)) {
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1));
                        victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0));
                        damage(victim, 0.8F, pet, ownerUuid);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 流沙陷坑：目标脚下变成流沙，一直往下陷。 */
    private static boolean quicksand(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        if (target == null) {
            return false;
        }
        final LivingEntity victimRef = target;
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (!victimRef.isAlive()) {
                    return false;
                }
                if (age % 3 == 0) {
                    for (int i = 0; i < 8; i++) {
                        double angle = i * (Math.PI * 2.0D / 8.0D) + age * 0.1D;
                        level.sendParticles(ParticleTypes.POOF, victimRef.getX() + Math.cos(angle) * 1.3D, victimRef.getY() + 0.05D,
                            victimRef.getZ() + Math.sin(angle) * 1.3D, 1, 0.05D, 0.02D, 0.05D, 0.0D);
                    }
                }
                for (LivingEntity victim : nearby(level, victimRef.getX(), victimRef.getY(), victimRef.getZ(), 2.0D, ownerUuid, pet, victimRef)) {
                    Vec3 v = victim.getDeltaMovement();
                    victim.setDeltaMovement(v.x * 0.45D, Math.min(v.y, -0.02D) - 0.06D, v.z * 0.45D);
                    victim.hurtMarked = true;
                    if (age % 20 == 0) {
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2));
                        damage(victim, 0.7F, pet, ownerUuid);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 疾风冲击：一股狂风把正前方的怪物吹散。 */
    private static boolean windGust(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 dir = target != null ? horizontal(pet.position(), target.position()).normalize() : lookDirection(pet);
        final double reach = rage ? 7.0D : 6.0D;
        pet.playSound(SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 1.6F);
        TASKS.add(new Task(level, ownerUuid, 16) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (!pet.isAlive()) {
                    return false;
                }
                for (int i = 0; i < 6; i++) {
                    double t = 0.5D + age * 0.9D + i * 0.4D;
                    if (t > reach) {
                        break;
                    }
                    level.sendParticles(ParticleTypes.CLOUD, pet.getX() + dir.x * t, pet.getY() + 1.0D, pet.getZ() + dir.z * t, 1, 0.3D, 0.3D, 0.3D, 0.02D);
                }
                if (age == 0) {
                    for (LivingEntity victim : nearby(level, pet.getX() + dir.x * reach * 0.5D, pet.getY(), pet.getZ() + dir.z * reach * 0.5D, reach, ownerUuid, pet, target)) {
                        damage(victim, rage ? 2.5F : 1.5F, pet, ownerUuid);
                    }
                    level.sendParticles(ParticleTypes.EXPLOSION, pet.getX() + dir.x, pet.getY() + 1.0D, pet.getZ() + dir.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
                for (LivingEntity victim : nearby(level, pet.getX() + dir.x * reach * 0.5D, pet.getY(), pet.getZ() + dir.z * reach * 0.5D, reach, ownerUuid, pet, target)) {
                    Vec3 rel = horizontal(pet.position(), victim.position());
                    if (rel.lengthSqr() > 1.0E-4D && rel.normalize().dot(dir) < 0.2D) {
                        continue;
                    }
                    victim.push(dir.x * 0.6D, 0.32D, dir.z * 0.6D);
                    level.sendParticles(ParticleTypes.CLOUD, victim.getX(), victim.getY() + 0.8D, victim.getZ(), 3, 0.2D, 0.2D, 0.2D, 0.05D);
                }
                return age < 16;
            }
        });
        return true;
    }

    // ================================================================== wave two: 爆发
    /** 乳山喷发：地面炸开一口奶，持续喷发。 */
    private static boolean dairyVolcano(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 center = target != null ? target.position() : pet.position().add(lookDirection(pet).scale(3.0D));
        final float power = rage ? 1.1F : 0.8F;
        pet.playSound(SoundEvents.GENERIC_EXPLODE, 1.0F, 0.6F);
        TASKS.add(new Task(level, ownerUuid, Math.min(dur, 60)) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age == 0) {
                    level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 0.6D, center.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
                if (age % 3 == 0) {
                    for (int i = 0; i < 6; i++) {
                        double angle = level.random.nextDouble() * Math.PI * 2.0D;
                        double r = level.random.nextDouble() * 2.5D;
                        level.sendParticles(ParticleTypes.LAVA, center.x + Math.cos(angle) * r, center.y + 0.3D, center.z + Math.sin(angle) * r,
                            1, 0.1D, 0.3D, 0.1D, 0.0D);
                        level.sendParticles(ParticleTypes.LANDING_LAVA, center.x + Math.cos(angle) * r, center.y + 0.1D, center.z + Math.sin(angle) * r,
                            1, 0.2D, 0.0D, 0.2D, 0.0D);
                    }
                }
                if (age % 10 == 0) {
                    boolean opening = age < 25;
                    for (LivingEntity victim : nearby(level, center.x, center.y, center.z, opening ? 4.5D : 3.2D, ownerUuid, pet, target)) {
                        damage(victim, opening ? power * 1.6F : power * 0.7F, pet, ownerUuid);
                        victim.setRemainingFireTicks(40);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 奶油地雷：在目标脚下埋三颗奶油雷，踩中就炸。 */
    private static boolean creamMine(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 base = target != null ? target.position() : pet.position().add(lookDirection(pet).scale(3.0D));
        final List<double[]> mines = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double angle = i * (Math.PI * 2.0D / 3.0D);
            mines.add(new double[]{base.x + Math.cos(angle) * 1.6D, base.y, base.z + Math.sin(angle) * 1.6D});
        }
        final boolean[] blown = new boolean[mines.size()];
        final int life = Math.max(dur, 100);
        TASKS.add(new Task(level, ownerUuid, life) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                for (int i = 0; i < mines.size(); i++) {
                    double[] m = mines.get(i);
                    if (blown[i]) {
                        continue;
                    }
                    if (age < 10) {
                        // 布雷动画：先亮起来再启动
                        level.sendParticles(ParticleTypes.ENTITY_EFFECT, m[0], m[1] + 0.15D + age * 0.04D, m[2], 3, 0.12D, 0.05D, 0.12D, 0.0D);
                        continue;
                    }
                    level.sendParticles(ParticleTypes.ENTITY_EFFECT, m[0], m[1] + 0.15D, m[2], 2, 0.15D, 0.05D, 0.15D, 0.0D);
                    List<LivingEntity> trigger = nearby(level, m[0], m[1], m[2], 1.3D, ownerUuid, pet, target);
                    if (trigger.isEmpty()) {
                        continue;
                    }
                    blown[i] = true;
                    level.sendParticles(ParticleTypes.EXPLOSION, m[0], m[1] + 0.4D, m[2], 1, 0.0D, 0.0D, 0.0D, 0.0D);
                    level.sendParticles(ParticleTypes.ITEM_SLIME, m[0], m[1] + 0.4D, m[2], 20, 0.5D, 0.5D, 0.5D, 0.1D);
                    pet.playSound(SoundEvents.GENERIC_EXPLODE, 0.7F, 1.4F);
                    for (LivingEntity victim : nearby(level, m[0], m[1], m[2], 2.6D, ownerUuid, pet, target)) {
                        damage(victim, 2.0F, pet, ownerUuid);
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 孢子爆裂：宠物打个喷嚏，喷出一团孢子云。 */
    private static boolean sporeBloom(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        final Vec3 dir = lookDirection(pet);
        final Vec3 from = pet.position().add(0.0D, 1.0D, 0.0D);
        pet.playSound(SoundEvents.SLIME_SQUISH, 0.9F, 0.6F);
        TASKS.add(new Task(level, ownerUuid, 24) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                for (int i = 0; i < 4; i++) {
                    double t = 1.0D + age * 0.35D + i * 0.6D;
                    if (t > 9.0D) {
                        break;
                    }
                    level.sendParticles(ParticleTypes.END_ROD, from.x + dir.x * t, from.y + Math.sin(t * 0.6D) * 0.4D, from.z + dir.z * t,
                        2, 0.35D, 0.35D, 0.35D, 0.01D);
                    level.sendParticles(ParticleTypes.MYCELIUM, from.x + dir.x * t, from.y + Math.sin(t * 0.6D) * 0.4D, from.z + dir.z * t,
                        1, 0.35D, 0.35D, 0.35D, 0.0D);
                }
                if (age % 6 == 0) {
                    double reach = 3.0D + age * 0.25D;
                    for (LivingEntity victim : nearby(level, from.x + dir.x * reach * 0.6D, from.y, from.z + dir.z * reach * 0.6D,
                        reach, ownerUuid, pet, target)) {
                        damage(victim, 0.6F, pet, ownerUuid);
                        victim.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 1));
                        victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 40, 0));
                        level.sendParticles(ParticleTypes.MYCELIUM, victim.getX(), victim.getY() + 1.0D, victim.getZ(), 10, 0.4D, 0.6D, 0.4D, 0.01D);
                    }
                }
                return age < 24;
            }
        });
        return true;
    }

    /** 雷击牛奶：天上一道雷劈向目标。 */
    private static boolean lightningMilk(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target) {
        if (target == null) {
            return false;
        }
        final Vec3 at = target.position();
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
            bolt.setVisualOnly(true);
            if (level.addFreshEntity(bolt)) {
                SkillCleanup.track(bolt, ownerUuid, 20, "lightning_milk");
            } else {
                bolt.remove();
            }
        }
        pet.playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.8F, 1.4F);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 2.0D, at.z, 30, 0.3D, 2.0D, 0.3D, 0.15D);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.4D, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        for (LivingEntity victim : nearby(level, at.x, at.y, at.z, 3.5D, ownerUuid, pet, target)) {
            SuperCowMod.recordDamageOwner(victim.getUUID(), ownerUuid);
            victim.hurt(DamageSource.LIGHTNING_BOLT, 4.0F);
            victim.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 100, 1));
            victim.setRemainingFireTicks(40);
        }
        // 落点周围再劈两道小雷，让它看起来真的在打雷
        for (int i = 0; i < 2; i++) {
            LightningBolt arc = EntityType.LIGHTNING_BOLT.create(level);
            if (arc == null) {
                continue;
            }
            double angle = Math.PI * 2.0D * i / 2.0D + 0.7D;
            arc.moveTo(at.x + Math.cos(angle) * 2.0D, at.y, at.z + Math.sin(angle) * 2.0D, 0.0F, 0.0F);
            arc.setVisualOnly(true);
            if (level.addFreshEntity(arc)) {
                SkillCleanup.track(arc, ownerUuid, 20, "lightning_milk");
            } else {
                arc.remove();
            }
        }
        return true;
    }

    /** 奔牛践踏：一群牛从宠物身后冲过去，把前路踏平。 */
    private static boolean stampede(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 dir = target != null ? horizontal(pet.position(), target.position()).normalize() : lookDirection(pet);
        int herd = rage ? 6 : 4;
        final List<UUID> cattle = new ArrayList<>();
        for (int i = 0; i < herd; i++) {
            Cow c = EntityType.COW.create(level);
            if (c == null) {
                continue;
            }
            double off = (i - (herd - 1) / 2.0D) * 1.8D;
            double px = pet.getX() - dir.x * (2.0D + i * 0.6D) - dir.z * off;
            double pz = pet.getZ() - dir.z * (2.0D + i * 0.6D) + dir.x * off;
            c.moveTo(px, pet.getY(), pz, pet.yRot, 0.0F);
            c.addTag("supercow_stampede");
            c.setNoAi(true);
            c.setInvulnerable(true);
            c.setSilent(true);
            if (!level.addFreshEntity(c)) {
                c.remove();
                continue;
            }
            SkillCleanup.track(c, ownerUuid, dur + 20, "stampede");
            cattle.add(c.getUUID());
        }
        if (cattle.isEmpty()) {
            return false;
        }
        pet.playSound(SoundEvents.COW_AMBIENT, 1.0F, 0.7F);
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                for (int i = 0; i < cattle.size(); i++) {
                    Entity entity = level.getEntity(cattle.get(i));
                    if (!(entity instanceof Mob) || !entity.isAlive()) {
                        continue;
                    }
                    Mob c = (Mob) entity;
                    c.moveTo(c.getX() + dir.x * 0.42D, c.getY(), c.getZ() + dir.z * 0.42D, pet.yRot, 0.0F);
                    if (age % 4 == 0) {
                        level.sendParticles(ParticleTypes.CLOUD, c.getX(), c.getY() + 0.2D, c.getZ(), 2, 0.25D, 0.05D, 0.25D, 0.0D);
                    }
                    if (age % 8 == 0) {
                        for (LivingEntity victim : nearby(level, c.getX(), c.getY(), c.getZ(), 1.8D, ownerUuid, pet, target)) {
                            damage(victim, 1.2F, pet, ownerUuid);
                            victim.push(dir.x * 0.4D, 0.2D, dir.z * 0.4D);
                        }
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 野牛冲锋：宠物自己变成炮弹，直线撞出去。 */
    private static boolean buffaloCharge(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 dir = target != null ? horizontal(pet.position(), target.position()).normalize() : lookDirection(pet);
        final int ticks = rage ? 22 : 16;
        final Set<UUID> hitOnce = new HashSet<>();
        pet.playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.0F, 0.8F);
        TASKS.add(new Task(level, ownerUuid, ticks) {
            @Override
            boolean tick(long now) {
                if (!pet.isAlive()) {
                    return false;
                }
                Vec3 v = pet.getDeltaMovement();
                pet.setDeltaMovement(dir.x * 0.75D, v.y, dir.z * 0.75D);
                pet.hurtMarked = true;
                level.sendParticles(ParticleTypes.CLOUD, pet.getX(), pet.getY() + 0.4D, pet.getZ(), 4, 0.3D, 0.1D, 0.3D, 0.02D);
                for (LivingEntity victim : nearby(level, pet.getX(), pet.getY(), pet.getZ(), 1.8D, ownerUuid, pet, target)) {
                    if (hitOnce.add(victim.getUUID())) {
                        damage(victim, rage ? 4.0F : 3.0F, pet, ownerUuid);
                    }
                    victim.push(dir.x * 0.9D, 0.35D, dir.z * 0.9D);
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== wave two: 增益
    /** 牛奶护盾：主人身上裹一层牛奶护盾（吸收 + 抗性）。 */
    private static boolean milkShield(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final ServerPlayer owner = owner(level, ownerUuid);
        if (owner == null) {
            return false;
        }
        owner.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, dur * 2, rage ? 1 : 0));
        owner.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, dur, 0));
        pet.heal(2.0F);
        level.sendParticles(ParticleTypes.END_ROD, owner.getX(), owner.getY() + 1.0D, owner.getZ(), 20, 0.5D, 0.8D, 0.5D, 0.05D);
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 4 == 0) {
                    ring(level, owner.getX(), owner.getY() + 1.0D, owner.getZ(), 0.9D + Math.sin(age * 0.2D) * 0.15D);
                }
                return true;
            }
        });
        return true;
    }

    /** 奶酪能量：主人短时间跑得快、跳得高、打得更疼。 */
    private static boolean cheesePower(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final ServerPlayer owner = owner(level, ownerUuid);
        if (owner == null) {
            return false;
        }
        int amp = rage ? 1 : 0;
        owner.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, dur, amp));
        owner.addEffect(new MobEffectInstance(MobEffects.JUMP, dur, amp));
        owner.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, dur, 0));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, owner.getX(), owner.getY() + 1.2D, owner.getZ(), 20, 0.6D, 0.8D, 0.6D, 0.1D);
        level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.GRASS_BREAK, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.3F);
        return true;
    }

    /** 哞吼：宠物仰天大吼，把周围的怪物吼退。 */
    private static boolean mooRoar(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 at = pet.position();
        final double radius = rage ? 8.0D : 6.5D;
        pet.playSound(SoundEvents.COW_AMBIENT, 1.4F, 0.6F);
        TASKS.add(new Task(level, ownerUuid, 20) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                double r = age * 0.45D;
                int n = Math.max(8, (int) (r * 6.0D));
                for (int i = 0; i < n; i++) {
                    double angle = i * (Math.PI * 2.0D / n);
                    level.sendParticles(ParticleTypes.ITEM_SLIME, at.x + Math.cos(angle) * r, at.y + 0.8D, at.z + Math.sin(angle) * r,
                        1, 0.1D, 0.1D, 0.1D, 0.0D);
                }
                if (age == 2) {
                    for (LivingEntity victim : nearby(level, at.x, at.y, at.z, radius, ownerUuid, pet, target)) {
                        Vec3 away = horizontal(at, victim.position());
                        double len = Math.max(0.01D, away.length());
                        victim.push(away.x / len * 0.9D, 0.35D, away.z / len * 0.9D);
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
                        damage(victim, 1.0F, pet, ownerUuid);
                    }
                }
                return age < 20;
            }
        });
        return true;
    }

    /** 牧草愈合：脚下冒出一片牧场，圈内友方持续回血。 */
    private static boolean pastureHeal(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 at = pet.position();
        final double radius = rage ? 7.0D : 5.5D;
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 3 == 0) {
                    for (int i = 0; i < 10; i++) {
                        double angle = i * (Math.PI * 2.0D / 10.0D) + age * 0.02D;
                        level.sendParticles(ParticleTypes.COMPOSTER, at.x + Math.cos(angle) * radius * 0.9D, at.y + 0.1D,
                            at.z + Math.sin(angle) * radius * 0.9D, 1, 0.1D, 0.05D, 0.1D, 0.0D);
                    }
                    level.sendParticles(ParticleTypes.HEART, at.x, at.y + 1.5D, at.z, 2, 0.8D, 0.4D, 0.8D, 0.0D);
                }
                if (age % 10 == 0) {
                    if (pet.isAlive()) {
                        pet.heal(2.0F);
                    }
                    for (ServerPlayer player : level.players()) {
                        if (player.isSpectator() || player.distanceToSqr(at.x, at.y, at.z) > radius * radius) {
                            continue;
                        }
                        player.heal(2.0F);
                        level.sendParticles(ParticleTypes.HEART, player.getX(), player.getY() + 1.6D, player.getZ(), 1, 0.2D, 0.2D, 0.2D, 0.0D);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 圣牛光环：宠物发光，脚下光环里的友方获得抗性与回血。 */
    private static boolean sacredBullAura(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        pet.addEffect(new MobEffectInstance(MobEffects.GLOWING, dur, 0));
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (!pet.isAlive()) {
                    return false;
                }
                if (age % 2 == 0) {
                    ring(level, pet.getX(), pet.getY() + 0.2D, pet.getZ(), 3.0D + Math.sin(age * 0.15D) * 0.3D);
                }
                if (age % 20 == 0) {
                    for (ServerPlayer player : level.players()) {
                        if (player.isSpectator() || player.distanceToSqr(pet.getX(), pet.getY(), pet.getZ()) > 36.0D) {
                            continue;
                        }
                        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, rage ? 1 : 0));
                        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 0));
                    }
                    for (LivingEntity ally : level.getEntitiesOfClass(LivingEntity.class, pet.getBoundingBox().inflate(6.0D))) {
                        if (ally == pet || !ally.isAlive() || !TargetValidator.isFriendlyEntity(ally, ownerUuid)) {
                            continue;
                        }
                        ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 0));
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 蛮牛之力：主人浑身冒红烟，力量 + 抗性，适合冲阵。 */
    private static boolean bullAura(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final ServerPlayer owner = owner(level, ownerUuid);
        if (owner == null) {
            return false;
        }
        owner.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, dur, rage ? 2 : 1));
        owner.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, dur, 0));
        owner.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, dur, 0));
        owner.playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.0F, 1.2F);
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 4 == 0) {
                    level.sendParticles(ParticleTypes.ANGRY_VILLAGER, owner.getX(), owner.getY() + 2.0D, owner.getZ(), 3, 0.4D, 0.4D, 0.4D, 0.0D);
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== wave two: 趣味
    /** 牛奶雨祝福：天上飘下牛奶，周围友方回血、火熄灭。 */
    private static boolean milkRainBlessing(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 at = pet.position();
        final double radius = rage ? 8.0D : 6.0D;
        pet.playSound(SoundEvents.COW_AMBIENT, 1.0F, 1.6F);
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                for (int i = 0; i < 4; i++) {
                    double angle = level.random.nextDouble() * Math.PI * 2.0D;
                    double r = Math.sqrt(level.random.nextDouble()) * radius;
                    level.sendParticles(ParticleTypes.ITEM_SNOWBALL, at.x + Math.cos(angle) * r, at.y + 5.0D, at.z + Math.sin(angle) * r,
                        1, 0.05D, 0.0D, 0.05D, 0.15D);
                }
                if (age % 3 == 0) {
                    for (ServerPlayer player : level.players()) {
                        if (player.isSpectator() || player.distanceToSqr(at) > radius * radius) {
                            continue;
                        }
                        if (age % 6 == 0) {
                            player.heal(1.0F);
                        }
                        player.clearFire();
                    }
                    for (LivingEntity ally : level.getEntitiesOfClass(LivingEntity.class, pet.getBoundingBox().inflate(radius))) {
                        if (!ally.isAlive() || !TargetValidator.isFriendlyEntity(ally, ownerUuid)) {
                            continue;
                        }
                        ally.clearFire();
                        if (age % 10 == 0) {
                            ally.heal(1.0F);
                        }
                    }
                }
                return true;
            }
        });
        return true;
    }

    private static void restorePetCanonicalName(Cow pet, UUID ownerUuid, TextComponent fallbackName) {
        if (pet == null || pet.removed) {
            return;
        }
        SuperCowMod mod = SuperCowMod.getInstance();
        net.dabbit.supercow.data.PetData data = mod != null ? mod.getPetData(ownerUuid) : null;
        if (data != null) {
            pet.setCustomName(new TextComponent("\u00A76\u00A7l\u8D85\u7EA7\u725B \u00A7eLv." + data.level));
            pet.setCustomNameVisible(true);
        } else if (fallbackName != null) {
            pet.setCustomName(fallbackName);
            pet.setCustomNameVisible(true);
        } else {
            pet.setCustomName(null);
            pet.setCustomNameVisible(false);
        }
    }

    /** 奶牛伪装：宠物变成一头普通奶牛，暂时不被打。 */
    private static boolean cowDisguise(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, int dur) {
        pet.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, dur, 0));
        for (LivingEntity around : level.getEntitiesOfClass(LivingEntity.class, pet.getBoundingBox().inflate(10.0D))) {
            if (around instanceof Mob && ((Mob) around).getTarget() == pet) {
                ((Mob) around).setTarget(null);
            }
        }
        final String curName = pet.hasCustomName() ? pet.getCustomName().getString() : null;
        final TextComponent oldName = (curName != null && !"一只普通奶牛".equals(curName)) ? new TextComponent(curName) : null;
        // Cancel any previous cowDisguise task for this owner so overlapping casts don't race on finish.
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (ownerUuid.equals(t.ownerUuid) && "cow_disguise".equals(t.tag)) {
                it.remove();
            }
        }
        pet.setCustomName(new TextComponent("一只普通奶牛"));
        pet.setCustomNameVisible(true);
        Task disguiseTask = new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                if (!pet.isAlive() || pet.removed) {
                    return false;
                }
                long age = now - startTick;
                if (age % 5 == 0) {
                    level.sendParticles(ParticleTypes.CLOUD, pet.getX(), pet.getY() + 0.8D, pet.getZ(), 6, 0.5D, 0.6D, 0.5D, 0.01D);
                    // 伪装期间怪物一锁定就失忆，真正做到“看起来就是头普通牛”
                    for (LivingEntity around : level.getEntitiesOfClass(LivingEntity.class, pet.getBoundingBox().inflate(8.0D))) {
                        if (around instanceof Mob && ((Mob) around).getTarget() == pet) {
                            ((Mob) around).setTarget(null);
                        }
                    }
                }
                if (age % 20 == 0 && level.random.nextInt(3) == 0) {
                    pet.playSound(SoundEvents.COW_AMBIENT, 0.5F, 1.8F);
                }
                return true;
            }

            @Override
            void onFinish() {
                restorePetCanonicalName(pet, ownerUuid, oldName);
                if (pet.isAlive() && !pet.removed) {
                    level.sendParticles(ParticleTypes.POOF, pet.getX(), pet.getY() + 1.0D, pet.getZ(), 20, 0.5D, 0.5D, 0.5D, 0.05D);
                }
            }
        };
        disguiseTask.tag = "cow_disguise";
        TASKS.add(disguiseTask);
        return true;
    }

    /** 哞鸣合唱：宠物开始唱歌，周围冒音符，怪物被搞得晕头转向。 */
    private static boolean mooChorus(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 at = pet.position();
        final double radius = rage ? 7.0D : 5.0D;
        pet.playSound(SoundEvents.NOTE_BLOCK_PLING, 1.0F, 0.8F);
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 4 == 0) {
                    double angle = age * 0.35D;
                    double r = 0.6D + (age % 20) * 0.12D;
                    level.sendParticles(ParticleTypes.NOTE, at.x + Math.cos(angle) * r, at.y + 1.2D + Math.sin(age * 0.2D) * 0.4D, at.z + Math.sin(angle) * r,
                        2, 0.2D, 0.2D, 0.2D, 1.0D);
                    if (age % 12 == 0) {
                        pet.playSound(SoundEvents.NOTE_BLOCK_HARP, 0.8F, 0.6F + level.random.nextFloat() * 0.8F);
                    }
                }
                if (age % 10 == 0) {
                    for (LivingEntity victim : nearby(level, at.x, at.y, at.z, radius, ownerUuid, pet, target)) {
                        victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 60, 0));
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0));
                        // 跟着拍子掉血，唱得越久拍子越密
                        damage(victim, age % 20 == 0 ? 0.8F : 0.4F, pet, ownerUuid);
                        victim.hurtMarked = true;
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 牛群蹦迪：周围刷出几头跟着节奏摇摆的奶牛。 */
    private static boolean herdDance(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        int count = rage ? 6 : 4;
        final List<UUID> dancers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Cow c = EntityType.COW.create(level);
            if (c == null) {
                continue;
            }
            double angle = i * (Math.PI * 2.0D / count);
            c.moveTo(pet.getX() + Math.cos(angle) * 2.0D, pet.getY(), pet.getZ() + Math.sin(angle) * 2.0D, (float) (angle * 57.2958D), 0.0F);
            c.addTag("supercow_dancer");
            c.setNoAi(true);
            c.setInvulnerable(true);
            c.setSilent(true);
            c.setBaby(true);
            if (!level.addFreshEntity(c)) {
                c.remove();
                continue;
            }
            SkillCleanup.track(c, ownerUuid, dur + 20, "herd_dance");
            dancers.add(c.getUUID());
        }
        if (dancers.isEmpty()) {
            return false;
        }
        pet.playSound(SoundEvents.NOTE_BLOCK_BASS, 1.0F, 0.7F);
        TASKS.add(new Task(level, ownerUuid, dur) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                for (int i = 0; i < dancers.size(); i++) {
                    Entity e = level.getEntity(dancers.get(i));
                    if (!(e instanceof Cow) || !e.isAlive()) {
                        continue;
                    }
                    Cow c = (Cow) e;
                    double jump = Math.max(0.0D, Math.sin((age + i * 4) * 0.4D)) * 0.45D;
                    c.moveTo(c.getX(), c.getY() + jump * 0.15D, c.getZ(), c.yRot + (float) Math.sin(age * 0.3D) * 6.0F, 0.0F);
                    level.sendParticles(ParticleTypes.NOTE, c.getX(), c.getY() + 1.3D, c.getZ(), 1, 0.15D, 0.15D, 0.15D, 1.0D);
                }
                if (age % 10 == 0) {
                    pet.playSound(SoundEvents.NOTE_BLOCK_PLING, 0.7F, 0.9F + level.random.nextFloat() * 0.6F);
                }
                // 踩点：每小节把圈里的怪震一下
                if (age % 20 == 0) {
                    for (LivingEntity victim : nearby(level, pet.getX(), pet.getY(), pet.getZ(), 4.0D, ownerUuid, pet, target)) {
                        victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 40, 0));
                        damage(victim, 0.6F, pet, ownerUuid);
                        level.sendParticles(ParticleTypes.NOTE, victim.getX(), victim.getY() + 1.4D, victim.getZ(), 2, 0.2D, 0.2D, 0.2D, 1.0D);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 钟声回响：敲响一口大钟，音波震荡附近怪物。 */
    private static boolean bellToll(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 at = pet.position();
        final double radius = rage ? 9.0D : 7.0D;
        pet.playSound(SoundEvents.BELL_BLOCK, 1.4F, 1.0F);
        TASKS.add(new Task(level, ownerUuid, 40) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 8 == 0) {
                    double r = 1.0D + age * 0.22D;
                    ring(level, at.x, at.y + 0.3D, at.z, r);
                    pet.playSound(SoundEvents.BELL_RESONATE, 0.8F, 1.0F + (float) age * 0.01F);
                }
                if (age == 1) {
                    for (LivingEntity victim : nearby(level, at.x, at.y, at.z, radius, ownerUuid, pet, target)) {
                        damage(victim, rage ? 2.0F : 1.0F, pet, ownerUuid);
                        victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 80, 0));
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
                    }
                    for (ServerPlayer player : level.players()) {
                        if (!player.isSpectator() && player.distanceToSqr(at) <= radius * radius) {
                            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0));
                        }
                    }
                }
                return age < 40;
            }
        });
        return true;
    }

    /** 牛奶喷泉：宠物原地喷出一柱牛奶，顺带把周围怪物淋懵。 */
    private static boolean milkFountain(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 at = pet.position();
        pet.playSound(SoundEvents.BUCKET_EMPTY, 1.2F, 0.8F);
        TASKS.add(new Task(level, ownerUuid, Math.min(dur, 60)) {
            @Override
            boolean tick(long now) {
                long age = now - startTick;
                if (age % 2 == 0) {
                    double angle = level.random.nextDouble() * Math.PI * 2.0D;
                    double speed = 0.35D + level.random.nextDouble() * 0.5D;
                    level.sendParticles(ParticleTypes.ITEM_SNOWBALL, at.x, at.y + 1.0D, at.z, 4,
                        Math.cos(angle) * speed * 0.3D, 0.6D + level.random.nextDouble() * 0.4D, Math.sin(angle) * speed * 0.3D, 0.05D);
                    level.sendParticles(ParticleTypes.DRIPPING_HONEY, at.x, at.y + 1.4D, at.z, 2, 0.15D, 0.2D, 0.15D, 0.0D);
                }
                if (age % 10 == 0) {
                    for (LivingEntity victim : nearby(level, at.x, at.y, at.z, 4.0D, ownerUuid, pet, target)) {
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1));
                        victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 60, 0));
                        damage(victim, 0.5F, pet, ownerUuid);
                    }
                }
                return true;
            }
        });
        return true;
    }

    /** 活塞草羊机：召唤实体草羊机关，高频活塞往复推刺，一下子把敌人顶飞上天。 */
    private static boolean pistonSheepMachine(ServerLevel level, Cow pet, UUID ownerUuid, LivingEntity target, boolean rage, int dur) {
        final Vec3 base = target != null && target.isAlive() ? target.position() : pet.position();
        final Vec3 dir = target != null ? horizontal(pet.position(), target.position()).normalize() : lookDirection(pet);
        Sheep sheep = EntityType.SHEEP.create(level);
        if (sheep == null) {
            return false;
        }
        double sx = base.x - dir.x * 1.4D;
        double sz = base.z - dir.z * 1.4D;
        sheep.moveTo(sx, base.y, sz, pet.yRot, 0.0F);
        sheep.setNoAi(true);
        sheep.setInvulnerable(true);
        sheep.setSilent(true);
        sheep.setCustomName(new TextComponent("§e§l活塞草羊机"));
        sheep.setCustomNameVisible(true);
        sheep.addTag("supercow_piston_sheep");
        if (!level.addFreshEntity(sheep)) {
            sheep.remove();
            return false;
        }
        final int runTicks = Math.min(dur, 48);
        SkillCleanup.track(sheep, ownerUuid, runTicks + 10, "piston_sheep_machine");
        final UUID sheepId = sheep.getUUID();
        level.playSound(null, sx, base.y, sz, SoundEvents.PISTON_EXTEND, net.minecraft.sounds.SoundSource.BLOCKS, 1.2F, 1.0F);
        TASKS.add(new Task(level, ownerUuid, runTicks) {
            @Override
            boolean tick(long now) {
                Entity e = level.getEntity(sheepId);
                if (!(e instanceof Sheep) || !e.isAlive()) {
                    return false;
                }
                long age = now - startTick;
                boolean extended = (age % 6) < 3;
                double offset = extended ? 1.1D : 0.2D;
                double cx = base.x - dir.x * (1.4D - offset);
                double cz = base.z - dir.z * (1.4D - offset);
                e.moveTo(cx, base.y, cz, pet.yRot, 0.0F);
                level.sendParticles(ParticleTypes.END_ROD, cx + dir.x * 0.6D, base.y + 0.7D, cz + dir.z * 0.6D,
                    4, 0.15D, 0.15D, 0.15D, 0.02D);
                if (age % 6 == 0) {
                    level.playSound(null, cx, base.y, cz, SoundEvents.PISTON_EXTEND, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.15F);
                    level.sendParticles(ParticleTypes.EXPLOSION, cx + dir.x * 0.8D, base.y + 0.6D, cz + dir.z * 0.8D,
                        2, 0.2D, 0.2D, 0.2D, 0.0D);
                    double lift = rage ? 1.35D : 1.05D;
                    double shove = rage ? 1.3D : 0.95D;
                    for (LivingEntity victim : nearby(level, cx + dir.x * 0.6D, base.y, cz + dir.z * 0.6D, 3.2D, ownerUuid, pet, target)) {
                        damage(victim, rage ? 2.5F : 1.5F, pet, ownerUuid);
                        victim.setDeltaMovement(dir.x * shove, lift, dir.z * shove);
                        victim.hurtMarked = true;
                    }
                } else if (age % 6 == 3) {
                    level.playSound(null, cx, base.y, cz, SoundEvents.PISTON_CONTRACT, net.minecraft.sounds.SoundSource.BLOCKS, 0.9F, 1.15F);
                }
                if (age + 1 >= runTicks) {
                    e.remove();
                    return false;
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== wave three

    /** Decorative actors never run vanilla AI, attacks, explosions or block changes. */
    private static Entity contraptionActor(ServerLevel level, UUID ownerUuid, EntityType<?> type,
                                          Vec3 at, String id, int ttl) {
        Entity actor = type.create(level);
        if (actor == null) return null;
        actor.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        actor.setNoGravity(true);
        actor.noPhysics = true; // Decorative actors must not shove players or other pets.
        actor.setInvulnerable(true);
        actor.setSilent(true);
        actor.addTag("supercow_quirk_actor");
        if (actor instanceof Mob) {
            ((Mob) actor).setNoAi(true);
            ((Mob) actor).setPersistenceRequired();
        }
        if (actor instanceof net.minecraft.world.entity.decoration.ArmorStand) {
            net.minecraft.world.entity.decoration.ArmorStand stand =
                (net.minecraft.world.entity.decoration.ArmorStand) actor;
            stand.setInvisible(true);
            // Marker stands cannot be ridden, edited, collided with or looted.
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            stand.saveWithoutId(tag);
            tag.putBoolean("Marker", true);
            stand.load(tag);
            net.minecraft.world.item.Item icon = Skills.byId(id).icon;
            stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                new net.minecraft.world.item.ItemStack(icon));
        }
        if (actor instanceof Sheep) {
            actor.setCustomName(new TextComponent("jeb_"));
        }
        if (!level.addFreshEntity(actor)) { actor.remove(); return null; }
        SkillCleanup.track(actor, ownerUuid, ttl, id);
        return actor;
    }

    /**
     * Thirty bounded choreographies. Actors are visual only; every hit passes the same
     * owner/friendly validator as the original skills. No real TNT, poison fish AI,
     * vehicles, blocks or lightning are used, so decorative effects cannot grief.
     */
    private static boolean contraption(final ServerLevel level, final Cow pet, final UUID ownerUuid,
                                       final LivingEntity target, final boolean rage, int dur, final String id) {
        if (TASKS.size() >= 96) return false;
        EntityType<?> type;
        int count = 1;
        switch (id) {
            case "anvil_guillotine": case "fishing_crane": case "end_rod_drill":
            case "bed_bounce_castle": case "boat_drift": case "minecart_rollercoaster":
                type = EntityType.ARMOR_STAND; break;
            case "sheep_rainbow_cannon": type = EntityType.SHEEP; break;
            case "tnt_hot_potato": type = EntityType.COW; break;
            case "slime_pinball": type = EntityType.SLIME; count = 3; break;
            case "villager_auction": type = EntityType.VILLAGER; break;
            case "chicken_gatling": type = EntityType.CHICKEN; break;
            case "creeper_fakeout": type = EntityType.CREEPER; break;
            case "pufferfish_minefield": type = EntityType.PUFFERFISH; count = 4; break;
            case "squid_ink_printer": type = EntityType.SQUID; break;
            case "golem_chiropractor": type = EntityType.IRON_GOLEM; break;
            case "llama_spit_turret": type = EntityType.LLAMA; count = 2; break;
            case "panda_bowling": type = EntityType.PANDA; break;
            case "cat_keyboard_walker": type = EntityType.CAT; count = 2; break;
            case "turtle_spinning_shell": type = EntityType.TURTLE; break;
            case "fox_berry_trap": type = EntityType.FOX; break;
            case "snowman_blender": type = EntityType.SNOW_GOLEM; count = 3; break;
            case "pig_rocket_express": type = EntityType.PIG; break;
            case "witch_cauldron_soup": type = EntityType.WITCH; break;
            case "skeleton_jazz_band": type = EntityType.SKELETON; count = 3; break;
            case "enderman_moving_company": type = EntityType.ENDERMAN; break;
            case "polar_bear_slap": type = EntityType.POLAR_BEAR; break;
            case "silverfish_tickle_pit": type = EntityType.SILVERFISH; count = 4; break;
            case "dolphin_surfboard": type = EntityType.DOLPHIN; break;
            case "mooshroom_nuclear_stew": type = EntityType.MOOSHROOM; break;
            case "bat_ufo_abduction": type = EntityType.BAT; count = 4; break;
            default: return false;
        }
        final int runTicks = Math.max(50, Math.min(80, dur));
        final Vec3 base = target != null && target.isAlive() ? target.position()
            : pet.position().add(lookDirection(pet).scale(4.0D));
        Vec3 heading = horizontal(pet.position(), base).normalize();
        final Vec3 dir = heading.lengthSqr() < 0.001D ? lookDirection(pet) : heading;
        final List<UUID> actors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Entity e = contraptionActor(level, ownerUuid, type, base, id, runTicks + 10);
            if (e != null) actors.add(e.getUUID());
        }
        if (actors.isEmpty()) return false;
        pet.playSound(SoundEvents.PISTON_EXTEND, 0.55F, 1.2F);
        TASKS.add(new Task(level, ownerUuid, runTicks) {
            private Vec3 aim = base;
            private int bounces;

            @Override
            boolean tick(long now) {
                if (!pet.isAlive() || pet.level != level) return false;
                int age = (int) (now - startTick);
                if (age >= runTicks) return false;
                // Sampling only each pulse bounds target searches and prevents following across worlds.
                if ((id.equals("tnt_hot_potato") || id.equals("turtle_spinning_shell")) && age % 12 == 1) {
                    List<LivingEntity> choices = nearby(level, base.x, base.y, base.z, 8.0D, ownerUuid, pet, target);
                    if (!choices.isEmpty()) aim = choices.get((bounces++) % choices.size()).position();
                }
                for (int i = 0; i < actors.size(); i++) {
                    Entity e = level.getEntity(actors.get(i));
                    if (e == null || !e.isAlive()) continue;
                    double phase = age * 0.25D + i * Math.PI * 2.0D / actors.size();
                    double x = base.x, y = base.y, z = base.z;
                    switch (id) {
                        case "anvil_guillotine": y += 4.0D * (1.0D - (age % 16) / 15.0D); break;
                        case "sheep_rainbow_cannon": x -= dir.x * 2; z -= dir.z * 2; y += 0.3D; break;
                        case "tnt_hot_potato": case "turtle_spinning_shell":
                            x = aim.x; z = aim.z; y = aim.y + 0.7D + Math.abs(Math.sin(age * 0.3D)); break;
                        case "fishing_crane": y += 5.0D; break;
                        case "slime_pinball": case "pufferfish_minefield":
                            x += Math.cos(i * Math.PI * 2 / actors.size()) * 2.5D;
                            z += Math.sin(i * Math.PI * 2 / actors.size()) * 2.5D;
                            y += Math.abs(Math.sin(phase)) * 0.3D; break;
                        case "villager_auction": x += 2.0D; y += Math.abs(Math.sin(phase)) * 0.2D; break;
                        case "end_rod_drill":
                            x += dir.x * (age % 16 * 0.22D - 2.0D);
                            z += dir.z * (age % 16 * 0.22D - 2.0D); y += 0.6D; break;
                        case "chicken_gatling": x = pet.getX(); z = pet.getZ(); y = pet.getY() + 2.4D; break;
                        case "boat_drift": case "minecart_rollercoaster":
                            x += Math.cos(phase) * 3; z += Math.sin(phase) * 3;
                            y += id.equals("boat_drift") ? 0 : 1.0D + Math.sin(age * 0.4D); break;
                        case "creeper_fakeout": x -= dir.x; z -= dir.z; y += Math.sin(age * 0.5D) * 0.1D; break;
                        case "bed_bounce_castle": y -= 1.2D; break;
                        case "squid_ink_printer": y += 3.0D; break;
                        case "golem_chiropractor": x -= dir.x * 1.5D; z -= dir.z * 1.5D; break;
                        case "llama_spit_turret": x += -dir.z * (i == 0 ? 2 : -2); z += dir.x * (i == 0 ? 2 : -2); break;
                        case "panda_bowling": case "pig_rocket_express": case "dolphin_surfboard":
                            double travel = age / (double) runTicks * 10 - 5;
                            x += dir.x * travel; z += dir.z * travel;
                            y += id.equals("pig_rocket_express") ? age * 0.04D : Math.abs(Math.sin(phase)) * 0.5D; break;
                        case "cat_keyboard_walker":
                            x += Math.sin(phase * 1.7D) * 2.5D; z += Math.cos(phase * 1.3D) * 2.5D; break;
                        case "fox_berry_trap": y += Math.abs(Math.sin(age * 0.18D)) * 1.5D; break;
                        case "snowman_blender": case "silverfish_tickle_pit": case "bat_ufo_abduction":
                            double r = id.equals("silverfish_tickle_pit") ? 0.7D : 2.0D;
                            x += Math.cos(phase) * r; z += Math.sin(phase) * r;
                            if (id.equals("bat_ufo_abduction")) y += 5.0D; break;
                        case "witch_cauldron_soup": x += 2.0D; break;
                        case "skeleton_jazz_band": x += (i - 1) * 1.8D; z += 2.0D; y += Math.abs(Math.sin(phase)) * 0.25D; break;
                        case "enderman_moving_company": x += (age / 16 % 2 == 0 ? -2 : 2); break;
                        case "polar_bear_slap": x -= dir.x * 2; z -= dir.z * 2; break;
                        case "mooshroom_nuclear_stew": y += age * 0.006D; break;
                        default: break;
                    }
                    e.moveTo(x, y, z, (float) (age * 18 + i * 90), 0.0F);
                    e.setDeltaMovement(Vec3.ZERO);
                }
                // Different pulse timings keep rapid-fire, three-hit and charged skills distinct.
                int interval = 16;
                boolean pulse;
                switch (id) {
                    case "chicken_gatling": interval = 4; break;
                    case "llama_spit_turret": case "silverfish_tickle_pit": interval = 8; break;
                    case "slime_pinball": case "turtle_spinning_shell": case "pufferfish_minefield": interval = 12; break;
                    case "golem_chiropractor": case "anvil_guillotine": interval = 16; break;
                    case "bat_ufo_abduction": case "snowman_blender": interval = 6; break;
                    default: break;
                }
                pulse = age > 0 && age % interval == 0;
                if (id.equals("anvil_guillotine") || id.equals("golem_chiropractor") || id.equals("enderman_moving_company"))
                    pulse = age == 16 || age == 32 || age == 48;
                if (id.equals("creeper_fakeout") || id.equals("mooshroom_nuclear_stew") || id.equals("polar_bear_slap"))
                    pulse = age == 40;
                if (age % 4 == 0) {
                    if (id.equals("bat_ufo_abduction") || id.equals("fishing_crane")) {
                        for (int h = 0; h <= 5; h++)
                            level.sendParticles(ParticleTypes.END_ROD, base.x, base.y + h, base.z, 1, 0.12D, 0.05D, 0.12D, 0);
                    } else if (id.equals("creeper_fakeout") || id.equals("tnt_hot_potato") || id.equals("mooshroom_nuclear_stew")) {
                        level.sendParticles(ParticleTypes.SMOKE, aim.x, aim.y + 1.2D, aim.z, 4, 0.3D, 0.3D, 0.3D, 0.01D);
                    }
                }
                if (!pulse) return true;
                Vec3 hit = id.equals("tnt_hot_potato") || id.equals("turtle_spinning_shell") ? aim : base;
                double radius = id.equals("mooshroom_nuclear_stew") ? 6.0D : 4.0D;
                net.minecraft.core.particles.SimpleParticleType particle = ParticleTypes.CRIT;
                switch (id) {
                    case "sheep_rainbow_cannon": case "cat_keyboard_walker": case "skeleton_jazz_band": particle = ParticleTypes.NOTE; break;
                    case "squid_ink_printer": particle = ParticleTypes.SQUID_INK; break;
                    case "dolphin_surfboard": case "llama_spit_turret": particle = ParticleTypes.SPLASH; break;
                    case "witch_cauldron_soup": particle = ParticleTypes.WITCH; break;
                    case "villager_auction": case "fox_berry_trap": particle = ParticleTypes.HAPPY_VILLAGER; break;
                    case "snowman_blender": particle = ParticleTypes.ITEM_SNOWBALL; break;
                    case "creeper_fakeout": particle = ParticleTypes.HEART; break;
                    case "enderman_moving_company": particle = ParticleTypes.PORTAL; break;
                    default: break;
                }
                level.sendParticles(particle, hit.x, hit.y + 1.0D, hit.z, 14, 1.5D, 0.8D, 1.5D, 0.04D);
                if (id.equals("sheep_rainbow_cannon") || id.equals("chicken_gatling") || id.equals("llama_spit_turret")) {
                    Vec3 origin = id.equals("chicken_gatling") ? pet.position().add(0, 2, 0) : base.add(dir.scale(-3));
                    for (int j = 0; j < 8; j++) {
                        Vec3 point = origin.add(hit.subtract(origin).scale(j / 7.0D));
                        level.sendParticles(particle, point.x, point.y + 0.7D, point.z, 1, 0, 0, 0, 0);
                    }
                }
                if (id.equals("skeleton_jazz_band") || id.equals("cat_keyboard_walker"))
                    pet.playSound(SoundEvents.NOTE_BLOCK_PLING, 0.5F, 0.6F + level.random.nextFloat());
                if (id.equals("anvil_guillotine")) pet.playSound(SoundEvents.ANVIL_LAND, 0.5F, 1.0F);
                if (id.equals("creeper_fakeout") || id.equals("mooshroom_nuclear_stew")) {
                    ring(level, hit.x, hit.y + 0.2D, hit.z, radius);
                    level.sendParticles(ParticleTypes.EXPLOSION, hit.x, hit.y + 1, hit.z, 3, 0.4D, 0.4D, 0.4D, 0);
                }
                for (LivingEntity victim : nearby(level, hit.x, hit.y, hit.z, radius, ownerUuid, pet, target)) {
                    if (victim instanceof Player) continue; // Even an explicit target cannot enable PvP here.
                    Vec3 push = horizontal(hit, victim.position()).normalize();
                    if (push.lengthSqr() < 0.001D) push = dir;
                    double lift = 0.25D, shove = 0.35D;
                    float amount = rage ? 1.5F : 0.8F;
                    switch (id) {
                        case "anvil_guillotine": lift = 0.12D; amount = rage ? 4 : 2; break;
                        case "sheep_rainbow_cannon": shove = 1.0D; lift = 0.5D; break;
                        case "tnt_hot_potato": lift = 0.65D; shove = 0.65D; break;
                        case "fishing_crane":
                            lift = age < 32 ? 0.5D : -0.7D; shove = 0; break;
                        case "slime_pinball":
                            push = new Vec3(Math.cos(age), 0, Math.sin(age)); shove = 1.15D; lift = 0.3D; break;
                        case "villager_auction":
                            victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 60, 0)); shove = 0.1D; break;
                        case "end_rod_drill": push = dir; shove = 0.8D; lift = 0.8D; break;
                        case "chicken_gatling": amount = rage ? 0.5F : 0.3F; lift = 0.08D; shove = 0.12D; break;
                        case "boat_drift": shove = 0.9D; break;
                        case "creeper_fakeout": lift = 0.9D; shove = 1; break;
                        case "pufferfish_minefield":
                            victim.addEffect(new MobEffectInstance(MobEffects.POISON, 40, 0)); lift = 0.45D; break;
                        case "bed_bounce_castle": lift = rage ? 1.25D : 0.95D; shove = 0; break;
                        case "squid_ink_printer":
                            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
                            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1)); shove = 0; break;
                        case "golem_chiropractor": lift = 1.0D; shove = 0.1D; amount = rage ? 3 : 2; break;
                        case "llama_spit_turret": push = dir; shove = 0.65D; break;
                        case "panda_bowling": push = dir; shove = 1.0D; lift = 0.6D; break;
                        case "cat_keyboard_walker":
                            switch (level.random.nextInt(4)) {
                                case 0: lift = 0.9D; break;
                                case 1: victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2)); break;
                                case 2: push = push.scale(-1); shove = 0.7D; break;
                                default: level.sendParticles(ParticleTypes.FIREWORK, victim.getX(), victim.getY() + 1, victim.getZ(), 8, 0.3D, 0.5D, 0.3D, 0.05D);
                            } break;
                        case "minecart_rollercoaster": lift = 0.7D; shove = 0.8D; break;
                        case "turtle_spinning_shell": shove = 1.1D; lift = 0.2D; break;
                        case "fox_berry_trap":
                            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 35, 3)); shove = 0; lift = 0; break;
                        case "snowman_blender":
                            push = new Vec3(-push.z, 0, push.x); lift = 0.45D; shove = 0.6D;
                            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 0)); break;
                        case "pig_rocket_express": push = dir; lift = 0.85D; shove = 0.65D; break;
                        case "witch_cauldron_soup":
                            victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 60, 0));
                            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0)); lift = 0.3D; break;
                        case "skeleton_jazz_band": lift = 0.45D; shove = 0.6D; break;
                        case "enderman_moving_company":
                            // Velocity rather than unsafe teleporting into blocks/unloaded chunks.
                            push = new Vec3(age == 32 ? -dir.x : dir.x, 0, age == 32 ? -dir.z : dir.z);
                            shove = 0.9D; lift = 0.6D; break;
                        case "polar_bear_slap": shove = rage ? 1.8D : 1.4D; lift = 0.65D; amount = rage ? 5 : 3; break;
                        case "silverfish_tickle_pit": amount = 0.4F; shove = 0; lift = 0.35D; break;
                        case "dolphin_surfboard": push = dir; lift = 0.75D; shove = 0.75D; break;
                        case "mooshroom_nuclear_stew": lift = 1.1D; shove = 1.1D; amount = rage ? 5 : 3; break;
                        case "bat_ufo_abduction":
                            push = horizontal(victim.position(), base).normalize(); lift = 0.28D; shove = 0.15D; amount = 0.4F; break;
                        default: break;
                    }
                    damage(victim, amount, pet, ownerUuid);
                    victim.setDeltaMovement(push.x * shove, lift, push.z * shove);
                    victim.hurtMarked = true;
                }
                return true;
            }

            @Override
            void onFinish() {
                for (UUID uuid : actors) {
                    Entity e = level.getEntity(uuid);
                    if (e != null) e.remove();
                }
            }
        });
        return true;
    }

    // ================================================================== infrastructure
    /** 每 tick 推进所有技能任务。 */
    public static void tickAll() {
        if (TASKS.isEmpty()) {
            return;
        }
        long now = SuperCowMod.getServerTicksStatic();
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            Task task = it.next();
            boolean alive;
            try {
                alive = now < task.endTick && task.tick(now);
            } catch (Exception e) {
                warn("quirk skill task crashed, dropping it", e);
                alive = false;
            }
            if (!alive) {
                it.remove();
                try {
                    task.onFinish();
                } catch (Exception e) {
                    warn("quirk skill task onFinish crashed", e);
                }
            }
        }
    }

    /** 主人下线/宠物消失时清掉它名下的所有状态。 */
    public static void cleanupForOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return;
        }
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task task = it.next();
            if (ownerUuid.equals(task.ownerUuid)) {
                it.remove();
                try {
                    task.onFinish();
                } catch (Exception e) {
                    warn("quirk skill task onFinish crashed", e);
                }
            }
        }
        SkillCleanup.cleanupForOwner(ownerUuid);
    }

    /** 服务器关闭时整体清空。 */
    public static void cleanupAll() {
        for (Task task : new ArrayList<>(TASKS)) {
            try {
                task.onFinish();
            } catch (Exception ignored) {
            }
        }
        TASKS.clear();
        SkillCleanup.cleanupAll();
    }

    public static int activeTasks() {
        return TASKS.size();
    }

    /** 这个工程没有接入日志框架，出错就打到 stdout，绝不让异常冒到服务端主线程。 */
    private static void warn(String message, Throwable error) {
        System.out.println("[SuperCow] " + message + ": " + error);
    }

    private static boolean isSolidBelow(ServerLevel level, Entity entity) {
        BlockPos below = new BlockPos(entity.getX(), entity.getY() - 0.1D, entity.getZ());
        return !level.getBlockState(below).isAir();
    }

    private static void ring(ServerLevel level, double x, double y, double z, double radius) {
        for (int i = 0; i < 36; i++) {
            double angle = i * (Math.PI * 2.0D / 36.0D);
            level.sendParticles(ParticleTypes.CLOUD, x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius,
                1, 0.05D, 0.02D, 0.05D, 0.0D);
        }
    }

    private static void removePositiveEffects(LivingEntity entity) {
        entity.removeEffect(MobEffects.MOVEMENT_SPEED);
        entity.removeEffect(MobEffects.DAMAGE_BOOST);
        entity.removeEffect(MobEffects.HEAL);
        entity.removeEffect(MobEffects.JUMP);
        entity.removeEffect(MobEffects.DAMAGE_RESISTANCE);
        entity.removeEffect(MobEffects.REGENERATION);
        entity.removeEffect(MobEffects.ABSORPTION);
    }

    /** 收集 radii 内的合法 AoE 目标（不含宠物、玩家、友方）。 */
    private static List<LivingEntity> nearby(ServerLevel level, double x, double y, double z, double radius, UUID ownerUuid, Cow pet, LivingEntity explicitTarget) {
        List<LivingEntity> out = new ArrayList<>();
        AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (candidate == pet || candidate.isDeadOrDying()) {
                continue;
            }
            if (!TargetValidator.isValidAoETarget(candidate, ownerUuid, explicitTarget)) {
                continue;
            }
            out.add(candidate);
        }
        return out;
    }

    private static List<LivingEntity> nearby(ServerLevel level, double x, double y, double z, double radius, UUID ownerUuid, Cow pet) {
        return nearby(level, x, y, z, radius, ownerUuid, pet, null);
    }

    private static void damage(LivingEntity victim, float amount, LivingEntity pet, UUID ownerUuid) {
        if (victim == null || !victim.isAlive() || amount <= 0.0F) {
            return;
        }
        SuperCowMod.recordDamageOwner(victim.getUUID(), ownerUuid);
        victim.hurt(DamageSource.mobAttack(pet), amount);
    }

    private static ServerPlayer owner(ServerLevel level, UUID ownerUuid) {
        if (ownerUuid == null) {
            return null;
        }
        return SuperCowMod.getInstance().getPlayer(ownerUuid);
    }

    /** 找最近的敌对目标（跳过主人、宠物和友方）。 */
    private static LivingEntity nearestTarget(ServerLevel level, Vec3 pos, double radius, UUID ownerUuid, Cow pet) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == pet || !e.isAlive() || !TargetValidator.isValidAoETarget(e, ownerUuid, null)) {
                continue;
            }
            double d = e.distanceToSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private static Vec3 horizontal(Vec3 from, Vec3 to) {
        return new Vec3(to.x - from.x, 0.0D, to.z - from.z);
    }

    private static Vec3 toward(Entity from, Entity to) {
        return horizontal(from.position(), to.position());
    }

    private static Vec3 direction(Entity from, Vec3 to) {
        return horizontal(from.position(), to);
    }

    private static Vec3 lookDirection(Entity entity) {
        Vec3 look = entity.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            double yaw = Math.toRadians(entity.yRot);
            return new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        }
        return flat.normalize();
    }

    /** 让 shooter 朝 target 甩出一发小投射物（雪球/箭），并纳入 SkillCleanup 跟踪。 */
    private static Projectile shootSmall(ServerLevel level, LivingEntity shooter, LivingEntity target, EntityType<?> type,
                                         net.minecraft.sounds.SoundEvent sound, String kind) {
        return shootSmall(level, shooter, target, type, sound, kind, null, 0);
    }

    private static Projectile shootSmall(ServerLevel level, LivingEntity shooter, LivingEntity target, EntityType<?> type,
                                         net.minecraft.sounds.SoundEvent sound, String kind, UUID ownerUuid, int ttl) {
        if (level == null || shooter == null || target == null || !target.isAlive()) {
            return null;
        }
        Entity created = type.create(level);
        if (!(created instanceof Projectile)) {
            if (created != null) {
                created.remove();
            }
            return null;
        }
        Vec3 from = new Vec3(shooter.getX(), shooter.getY() + shooter.getEyeHeight() - 0.15D, shooter.getZ());
        Vec3 dir = target.getEyePosition(1.0F).subtract(from);
        double dist = dir.length();
        if (dist < 0.1D) {
            created.remove();
            return null;
        }
        Vec3 unit = dir.scale(1.0D / dist);
        Projectile projectile = (Projectile) created;
        projectile.setOwner(shooter);
        projectile.moveTo(from.x, from.y, from.z, shooter.yRot, shooter.xRot);
        projectile.shoot(unit.x, unit.y, unit.z, 1.6F, 2.0F);
        if (!level.addFreshEntity(projectile)) {
            projectile.remove();
            return null;
        }
        if (ownerUuid != null) {
            projectile.addTag("supercow_skill");
            SkillCleanup.track(projectile, ownerUuid, Math.max(40, ttl), kind == null ? "projectile" : kind);
        }
        if (sound != null) {
            level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), sound, shooter.getSoundSource(), 0.8F, 1.2F);
        }
        return projectile;
    }

    @SafeVarargs
    private static <T> Set<T> setOf(T... values) {
        Set<T> set = new HashSet<>(Arrays.asList(values));
        return set;
    }

    /** 周期任务基类：超时或 tick 返回 false 即被移除。 */
    private abstract static class Task {
        final ServerLevel level;
        final UUID ownerUuid;
        final long startTick;
        final long endTick;
        String tag;

        Task(ServerLevel level, UUID ownerUuid, int duration) {
            this.level = level;
            this.ownerUuid = ownerUuid;
            this.startTick = SuperCowMod.getServerTicksStatic();
            this.endTick = startTick + Math.max(1L, (long) duration);
        }

        abstract boolean tick(long now);

        void onFinish() {
        }
    }
}
