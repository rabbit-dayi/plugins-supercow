package net.dabbit.supercow.combat;

import java.util.*;
import net.dabbit.supercow.SuperCowMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Twenty-five bounded, non-griefing visual skills. All damage/impulses use the common balance layer. */
public final class AbsurdSkills {
    private static final String[] IDS = {"slime_trampoline", "magnet_milk", "redstone_disco", "paper_airplane", "cheese_meteor", "umbrella_spin", "clockwork_carrot", "dispenser_popcorn", "rubber_duck_fleet", "tofu_guillotine", "gravity_yo_yo", "milk_tea_tornado", "bell_metronome", "pumpkin_bowling", "bread_frisbee", "fishing_moon", "bookshelf_domino", "potato_satellite", "carpet_magic_show", "watermelon_catapult", "lantern_jellyfish", "hopper_vacuum", "cake_conveyor", "amethyst_orchestra", "sock_black_hole"};
    private static final List<Show> TASKS = new ArrayList<>();
    private AbsurdSkills() { }
    private static int index(String id) {
        if (id == null) return -1;
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }
    public static boolean supports(String id) { return index(id) >= 0; }
    public static int activeTasks() { return TASKS.size(); }
    public static boolean cast(ServerLevel level, Cow pet, UUID owner, LivingEntity target,
                               boolean rage, String id, int duration) {
        int kind = index(id);
        if (kind < 0 || level == null || pet == null || owner == null || !pet.isAlive() || TASKS.size() >= 48) return false;
        int count = 0;
        for (Show show : TASKS) if (owner.equals(show.owner)) count++;
        if (count >= 4) return false;
        Vec3 at = target != null && target.isAlive() && target.level == level && target.distanceToSqr(pet) <= 256
            ? target.position() : pet.position().add(pet.getLookAngle().scale(3));
        Show show = new Show(level, pet, owner, target, rage, kind, at, Math.max(50, Math.min(80, duration)));
        try {
            for (int i = 0; i < 3; i++) {
                ArmorStand stand = EntityType.ARMOR_STAND.create(level);
                if (stand == null) continue;
                show.actors.add(stand); // Register the reference before any fallible initialization.
                stand.moveTo(at.x, at.y + .3, at.z, 0, 0);
                stand.setNoGravity(true); stand.noPhysics = true; stand.setInvulnerable(true); stand.setSilent(true);
                stand.setInvisible(true); stand.addTag("supercow_quirk_actor");
                CompoundTag tag = new CompoundTag(); stand.saveWithoutId(tag);
                tag.putBoolean("Marker", true); tag.putInt("DisabledSlots", 4144959); stand.load(tag);
                stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Skills.byId(id).icon));
                if (!level.addFreshEntity(stand)) { stand.remove(); continue; }
                SkillCleanup.track(stand, owner, show.duration + 20, id);
            }
            boolean any = false; for (ArmorStand actor : show.actors) if (!actor.removed) any = true;
            if (!any) { show.finish(); return false; }
            TASKS.add(show); return true;
        } catch (RuntimeException error) {
            show.finish(); System.out.println("[SuperCow] wave4 cast " + id + ": " + error); return false;
        }
    }
    public static void tick(long now) {
        for (Iterator<Show> it = TASKS.iterator(); it.hasNext();) {
            Show show = it.next(); boolean keep;
            try { keep = show.step(now); }
            catch (RuntimeException error) { keep = false; System.out.println("[SuperCow] wave4 task: " + error); }
            if (!keep) { it.remove(); show.finish(); }
        }
    }
    public static void cleanupOwner(UUID owner) {
        for (Iterator<Show> it = TASKS.iterator(); it.hasNext();) {
            Show show = it.next(); if (show.owner.equals(owner)) { it.remove(); show.finish(); }
        }
    }
    public static void clearAll() { for (Show show : TASKS) show.finish(); TASKS.clear(); }
    private static final class Show {
        final ServerLevel level; final Cow pet; final UUID owner; final LivingEntity target;
        final boolean rage; final int kind, duration; final Vec3 center; final long start;
        final List<ArmorStand> actors = new ArrayList<>();
        Show(ServerLevel level, Cow pet, UUID owner, LivingEntity target, boolean rage, int kind, Vec3 center, int duration) {
            this.level=level; this.pet=pet; this.owner=owner; this.target=target; this.rage=rage;
            this.kind=kind; this.center=center; this.duration=duration; this.start=SuperCowMod.getServerTicksStatic();
        }
        boolean step(long now) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
            if (!pet.isAlive() || pet.removed || pet.level != level || player == null || player.level != level
                || now - start >= duration || !level.hasChunkAt(new net.minecraft.core.BlockPos(center))) return false;
            int age=(int)(now-start); double t=age*.13;
            for (int i=0; i<actors.size(); i++) {
                ArmorStand actor=actors.get(i); if (actor.removed) continue;
                Vec3 offset=orbit(kind,t,i,age);
                actor.moveTo(center.x+offset.x,center.y+offset.y,center.z+offset.z,(float)(age*9+i*120),0);
                actor.setDeltaMovement(Vec3.ZERO);
                if (age%4==0) {
                    if (kind==2 || kind==12 || kind==23) level.sendParticles(ParticleTypes.NOTE,actor.getX(),actor.getY()+.4,actor.getZ(),1,0,0,0,0);
                    else if (kind==1 || kind==10 || kind==24) level.sendParticles(ParticleTypes.PORTAL,actor.getX(),actor.getY()+.3,actor.getZ(),2,.1,.1,.1,.01);
                    else if (kind==8 || kind==11 || kind==20) level.sendParticles(ParticleTypes.BUBBLE,actor.getX(),actor.getY()+.3,actor.getZ(),2,.1,.1,.1,0);
                    else level.sendParticles(ParticleTypes.END_ROD,actor.getX(),actor.getY()+.3,actor.getZ(),1,.05,.05,.05,.01);
                }
            }
            // Three bounded pulses; no per-tick entity query and no sustained burning/poison.
            if (age==12 || age==30 || age==48) pulse(age);
            return true;
        }
        void pulse(int age) {
            int hits=0;
            for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,new AABB(center,center).inflate(3.2))) {
                if (victim instanceof Player || victim.distanceToSqr(center)>3.2*3.2
                    || !TargetValidator.isValidAoETarget(victim,owner,target)) continue;
                if (++hits>8) break;
                Vec3 delta=victim.position().subtract(center); Vec3 outward=new Vec3(delta.x,0,delta.z);
                if (outward.lengthSqr()<.001) outward=new Vec3(1,0,0); else outward=outward.normalize();
                double shove=.22,lift=.04; float damage=rage?1.2F:.8F;
                switch(kind) {
                    case 0: lift=.45; shove=0; break;
                    case 1: shove=-.35; lift=0; slow(victim); break;
                    case 2: weak(victim); shove=.12; break;
                    case 3: shove=.32; lift=.02; break;
                    case 4: shove=.3; lift=.18; damage=rage?1.6F:1.0F; break;
                    case 5: outward=new Vec3(-outward.z,0,outward.x); shove=.38; break;
                    case 6: slow(victim); lift=.14; shove=.1; break;
                    case 7: lift=.28; shove=.16; break;
                    case 8: outward=new Vec3(Math.cos(age*.2),0,Math.sin(age*.2)); shove=.3; break;
                    case 9: weak(victim); lift=.02; damage=1.0F; break;
                    case 10: shove=age==30?-.25:.18; lift=age==30?-.12:.22; break;
                    case 11: shove=-.22; lift=.16; break;
                    case 12: slow(victim); shove=age==30?-.18:.18; break;
                    case 13: outward=new Vec3(1,0,0); shove=.42; lift=.03; break;
                    case 14: outward=new Vec3(0,0,age==30?-1:1); shove=.28; break;
                    case 15: lift=.32; shove=-.12; break;
                    case 16: weak(victim); shove=.12; lift=0; break;
                    case 17: shove=.3; lift=.08; break;
                    case 18: slow(victim); lift=.22; shove=0; break;
                    case 19: shove=.36; lift=.2; break;
                    case 20: weak(victim); shove=-.15; lift=.08; break;
                    case 21: slow(victim); shove=-.4; lift=0; break;
                    case 22: outward=new Vec3(1,0,0); shove=.2; lift=0; break;
                    case 23: weak(victim); shove=.1; lift=.05; break;
                    case 24: shove=-.32; lift=.02; break;
                    default: break;
                }
                SuperCowMod.recordDamageOwner(victim.getUUID(),owner);
                victim.hurt(DamageSource.mobAttack(pet),damage); // Exactly one central multiplier in LivingEntityMixin.
                CombatBalance.push(victim,outward.x*shove,lift,outward.z*shove); victim.hurtMarked=true;
            }
        }
        void slow(LivingEntity v) { v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,25,0)); }
        void weak(LivingEntity v) { v.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,25,0)); }
        void finish() { for (ArmorStand actor:actors) if (actor!=null && !actor.removed) actor.remove(); actors.clear(); }
    }
    private static Vec3 orbit(int k,double t,int i,int age) {
        double a=t+i*Math.PI*2/3, u=i-1, f=(age%24)/24.0;
        switch(k) {
            case 0: return new Vec3(u*1.1,.3+Math.abs(Math.sin(t*2+i))*1.7,Math.cos(t)*.4);
            case 1: return new Vec3(Math.cos(a)*1.7,.6,Math.sin(a)*1.7);
            case 2: return new Vec3(Math.signum(Math.cos(a))*1.3,.5+Math.abs(Math.sin(t*2))*.8,Math.signum(Math.sin(a))*1.3);
            case 3: return new Vec3(-2+f*4,1+Math.sin(f*Math.PI),u*(.3+f));
            case 4: return new Vec3(Math.cos(a)*(1-f),3.2-f*3,Math.sin(a)*(1-f));
            case 5: return new Vec3(Math.cos(a)*2,1.3+Math.sin(t)*.15,Math.sin(a)*2);
            case 6: return new Vec3(Math.cos(a)*(1.2+.3*Math.cos(6*a)),.4+Math.abs(Math.sin(t*3+i))*.8,Math.sin(a)*(1.2+.3*Math.cos(6*a)));
            case 7: return new Vec3(u*.7,f*3*(1-f)*3, -1+f*3);
            case 8: return new Vec3(-1.5+f*3,.3,Math.sin(t+i)*.8+u*.5);
            case 9: return new Vec3(u*.9,2.4-((age+i*8)%24)/12.0,0);
            case 10: return new Vec3(u*.5,.4+(Math.sin(t*2+i)+1)*1.2,Math.cos(t)*.3);
            case 11: return new Vec3(Math.cos(a)*(1-f),.2+f*2.8,Math.sin(a)*(1-f));
            case 12: return new Vec3(Math.sin(t*2+i)*1.6,1.7-Math.abs(Math.sin(t*2+i))*.6,u*.4);
            case 13: return new Vec3(-2+4*f,.2+Math.abs(Math.sin(t*3))*.15,u*.6);
            case 14: return new Vec3(Math.sin(a)*2,.7,Math.sin(a*2));
            case 15: return new Vec3(u*.4,1.5+Math.sin(t)*.8,Math.cos(t)*.6);
            case 16: return new Vec3(u*1.1,.4+Math.max(0,1-((age+i*12)%36)/18.0),Math.sin(t+i)*.3);
            case 17: return new Vec3(Math.cos(a)*2,1+Math.sin(a)*.7,Math.sin(a)*1.3);
            case 18: return new Vec3(u*.8,.5+Math.sin(t*2+i)*.4,Math.sin(t)*1.3);
            case 19: return new Vec3(-2+f*4,.2+4*f*(1-f)*2.6,u*.6);
            case 20: return new Vec3(Math.cos(a)*(1+.2*Math.sin(t*3)),1.4+Math.sin(t*2+i)*.5,Math.sin(a));
            case 21: return new Vec3(Math.cos(a)*(1.8-f),1.6-f,Math.sin(a)*(1.8-f));
            case 22: return new Vec3(-2+((age+i*16)%48)/12.0,.4,u*.7);
            case 23: return new Vec3(u*.9,.7+Math.abs(Math.sin(t*(i+1)))*1.2,Math.sin(t+i)*.5);
            case 24: return new Vec3(Math.cos(a*2)*(2-1.7*f),.6,Math.sin(a*2)*(2-1.7*f));
            default: return Vec3.ZERO;
        }
    }
}
