package net.dabbit.supercow.combat;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for the skill roster: ids, display names, weights and GUI icons.
 *
 * The 1.19.4 plugin had a hard-coded weighted table; the port keeps those weights and appends
 * the ten "abstract" skills that were added later. {@code sky_spiral} is a movement quirk, so it
 * has weight 0 and is never rolled by the combat table.
 */
public final class Skills {

    public static final String SKY_SPIRAL = "sky_spiral";

    public static final class Skill {
        public final String id;
        public final String display;
        public final String hint;
        public final int weightNormal;
        public final int weightRage;
        public final Item icon;
        /** false = 只能在 GUI/命令里查，战斗时不会被抽到 */
        public final boolean rollable;

        Skill(String id, String display, String hint, int weightNormal, int weightRage, Item icon, boolean rollable) {
            this.id = id;
            this.display = display;
            this.hint = hint;
            this.weightNormal = weightNormal;
            this.weightRage = weightRage;
            this.icon = icon;
            this.rollable = rollable;
        }
    }

    private static final List<Skill> ALL = new ArrayList<>();

    static {
        // ---- original 1.19.4 roster (weights from CombatManager.kt) ----
        add("arrow", "箭矢", "主输出，命中后 35% 附加箭矢效果", 950, 900, Items.ARROW, true);
        add("firework", "烟花", "朝目标上方发射烟花，范围伤害", 15, 30, Items.FIREWORK_ROCKET, true);
        add("potion", "药水", "投掷药水，落点范围伤害 + 负面效果", 15, 30, Items.SPLASH_POTION, true);
        add("fireball", "火球", "小火球，命中点燃（不破坏方块）", 5, 15, Items.FIRE_CHARGE, true);
        add("exploding_cow", "自爆牛", "发射一只会爆炸的牛，引信 30 tick", 5, 10, Items.TNT, true);
        add("chicken", "轰炸鸡", "头顶刷轰炸鸡，边飞边下蛋", 3, 5, Items.EGG, true);
        add("music", "音乐", "音波范围伤害 + 减速/虚弱", 3, 5, Items.NOTE_BLOCK, true);
        add("summon", "护卫小牛", "召唤 2~3 只小牛协战 20 秒", 2, 2, Items.COW_SPAWN_EGG, true);
        add("summon_parrots", "鹦鹉", "环绕啄击的鹦鹉", 1, 2, Items.PARROT_SPAWN_EGG, true);
        add("summon_rabbits", "兔子", "冲向目标啃咬的兔子", 1, 1, Items.RABBIT_SPAWN_EGG, true);

        // ---- ten abstract skills added in this update ----
        add("cow_rain", "奶牛雨", "天上掉下几只牛，砸一下就走", 8, 14, Items.SADDLE, true);
        add("milk_bomb", "牛奶炸弹", "泼一身牛奶：清除增益 + 缓慢", 8, 14, Items.MILK_BUCKET, true);
        add("moon_walk", "月球漫步", "宠物自己跳得又高又飘", 8, 14, Items.RABBIT_FOOT, true);
        add("moo_wave", "哞叫波", "两圈音波把周围推开", 8, 14, Items.BELL, true);
        add("hay_tornado", "干草旋风", "干草绕着目标转，把怪慢慢吸进去", 8, 14, Items.HAY_BLOCK, true);
        add("butterfly_swarm", "蝴蝶群", "围着目标乱飞乱舔", 8, 14, Items.BAT_SPAWN_EGG, true);
        add("zero_gravity", "零重力", "附近怪物一起飘起来", 8, 14, Items.SHULKER_SHELL, true);
        add("mirror_cow", "镜像牛", "刷一只无敌的假牛替你挨打", 8, 14, Items.NAME_TAG, true);
        add("teleport_swap", "换位", "和目标的坐标瞬间对调", 8, 14, Items.ENDER_PEARL, true);
        add("confetti", "彩纸爆炸", "一堆烟花粒子，伤害低，很吵", 8, 14, Items.GUNPOWDER, true);

        // ---- movement quirk, not part of the combat roll ----
        add(SKY_SPIRAL, "转圈飞天", "追击时偶尔把自己寻路到天上", 0, 0, Items.FEATHER, false);
    }

    private static void add(String id, String display, String hint, int wn, int wr, Item icon, boolean rollable) {
        ALL.add(new Skill(id, display, hint, wn, wr, icon, rollable));
    }

    public static List<Skill> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static Skill byId(String id) {
        if (id == null) {
            return null;
        }
        for (Skill s : ALL) {
            if (s.id.equalsIgnoreCase(id)) {
                return s;
            }
        }
        return null;
    }

    public static String displayOf(String id) {
        Skill s = byId(id);
        return s != null ? s.display : String.valueOf(id);
    }

    private Skills() {
    }
}
