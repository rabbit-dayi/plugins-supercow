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
 *
 * The forty skills of the "第二弹" batch (see the EXTRA block at the end of the static initializer)
 * are all rollable quirks implemented by {@link QuirkSkills}. Their personal weights stay in the
 * 1..5 band and add up to 141, so the normal roll still lands on {@code arrow} (950) about 82% of the
 * time: the new skills are flavour, never the main damage source.
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

        // =====================================================================================
        // ---- 第二弹：40 个可抽技能（全部由 QuirkSkills 实现，权重 1~5，合计 141）----
        // =====================================================================================

        // ---- 位移 8 ----
        add("moo_dash", "蛮牛冲撞", "低头蓄力后横冲过去，沿途撞飞并击退", 5, 10, Items.LEATHER, true);
        add("sapling_launch", "树苗弹射", "种下的树苗瞬间长大，把宠物弹上天空", 4, 8, Items.OAK_SAPLING, true);
        add("earth_dive", "遁地冲锋", "钻进地面从目标脚下钻出并抛起", 4, 8, Items.DIRT, true);
        add("wormhole", "虫洞", "原地留下虫洞，10 tick 后瞬移回去", 4, 8, Items.ENDER_EYE, true);
        add("grapple_rope", "套索飞爪", "射出套索，把自己拉向目标", 3, 6, Items.LEAD, true);
        add("tumble_roll", "翻滚", "向侧面滚出去，顺便拍开路上的怪", 3, 6, Items.RABBIT_HIDE, true);
        add("spring_step", "弹簧步", "在原地弹来弹去，弹一下就踩一下周围", 3, 6, Items.SLIME_BALL, true);
        add("meteor_jump", "陨石跳", "跳起来直接砸向目标头顶", 3, 6, Items.MAGMA_CREAM, true);

        // ---- 召唤 7 ----
        add("bee_swarm", "蜂群", "放出一群蜜蜂，绕着刷怪点穷追不舍", 4, 8, Items.HONEYCOMB, true);
        add("iron_calf", "铁犊子", "召唤一只无敌铁傀儡顶在前面", 3, 6, Items.IRON_INGOT, true);
        add("wolf_pack", "狼群", "三只狼从两侧包抄", 4, 8, Items.BONE, true);
        add("snow_golem_turret", "雪人炮台", "雪人立在宠物头顶扔雪球", 3, 6, Items.SNOWBALL, true);
        add("fire_cracker_chain", "鞭炮串", "串成一挂的鞭炮炸个不停", 2, 5, Items.FIREWORK_STAR, true);
        add("barn_totem", "仓鼠图腾", "插一根图腾，持续放箭", 2, 5, Items.OAK_FENCE, true);
        add("phantom_wings", "幻翼之翼", "招出一只幻翼在头顶盘旋扫射", 2, 4, Items.PHANTOM_MEMBRANE, true);

        // ---- 控制 7 ----
        add("mud_trap", "泥潭", "脚下一片泥，踩进去的怪物打滑", 4, 8, Items.CLAY_BALL, true);
        add("ice_field", "冰封领域", "铺一层冰，怪物站不住脚", 4, 8, Items.ICE, true);
        add("sleep_beam", "催眠光波", "射一道光，打中的怪直接原地发呆", 3, 6, Items.ENDER_EYE, true);
        add("lasso_yank", "缰绳拖拽", "套住目标往宠物这边拽", 3, 6, Items.STRING, true);
        add("blizzard", "暴风雪", "风雪糊住视野，怪物又慢又冷", 3, 6, Items.ICE, true);
        add("quicksand", "流沙陷坑", "目标脚下一沉，往下陷", 2, 5, Items.SAND, true);
        add("wind_gust", "疾风冲击", "一股狂风正面吹散怪群", 3, 6, Items.FEATHER, true);

        // ---- 爆发 6 ----
        add("dairy_volcano", "乳山喷发", "脚下喷出一道牛奶柱，范围伤害", 5, 10, Items.SHULKER_BOX, true);
        add("cream_mine", "奶油地雷", "埋一颗奶油地雷，踩上去就炸", 4, 8, Items.CAKE, true);
        add("spore_bloom", "孢子爆裂", "爆开孢子云，中毒 + 视线受阻", 3, 6, Items.RED_MUSHROOM, true);
        add("lightning_milk", "雷击牛奶", "把一道雷引到目标身上", 3, 6, Items.TRIPWIRE_HOOK, true);
        add("stampede", "奔牛践踏", "一群牛从身后呼啸而过", 4, 8, Items.BEEF, true);
        add("buffalo_charge", "野牛冲锋", "蓄力三连冲，最后一下最疼", 3, 6, Items.BEEF, true);

        // ---- 增益 6 ----
        add("milk_shield", "牛奶护盾", "给主人和宠物套上吸收护盾", 5, 8, Items.SHIELD, true);
        add("cheese_power", "奶酪之力", "吃一口奶酪，宠物攻击力暴涨", 4, 8, Items.CAKE, true);
        add("moo_roar", "哞吼", "一声吼，主人宠物一起提速", 4, 8, Items.WHITE_WOOL, true);
        add("pasture_heal", "牧草愈合", "脚下一圈草，主人和宠物一起回血", 4, 8, Items.WHEAT, true);
        add("sacred_bull_aura", "圣牛光环", "踩着光环，回血 + 抗性 + 发光", 3, 6, Items.GLOWSTONE_DUST, true);
        add("bull_aura", "霸体冲锋", "免疫击退，血上限临时加一截", 2, 4, Items.LEATHER_HORSE_ARMOR, true);

        // ---- 趣味 6 ----
        add("milk_rain_blessing", "牛奶祝福雨", "一阵牛奶雨，淋到的怪反而变慢", 3, 5, Items.MILK_BUCKET, true);
        add("cow_disguise", "伪装成牛", "变成一坨牛粪，怪物闻不到宠物", 3, 5, Items.BROWN_WOOL, true);
        add("moo_chorus", "哞哞合唱团", "三只牛摆开合唱，音波扫射", 3, 5, Items.NOTE_BLOCK, true);
        add("herd_dance", "群牛舞", "原地跳踢踏舞，踩得周围直晃", 3, 5, Items.MUSIC_DISC_CAT, true);
        add("bell_toll", "牛铃摇响", "摇响大钟，吓退一圈怪物", 4, 8, Items.BELL, true);
        add("milk_fountain", "奶泉喷涌", "脚下喷泉，周围全是奶香粒子", 2, 4, Items.MILK_BUCKET, true);
        add("piston_sheep_machine", "活塞草羊机", "刷出实体活塞草羊机，往复推刺一下子把敌人顶飞", 4, 8, Items.PISTON, true);
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
