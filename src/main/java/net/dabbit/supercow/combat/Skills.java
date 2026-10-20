package net.dabbit.supercow.combat;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for the skill roster: ids, display names, short hints, flavour intros,
 * weights and GUI icons.
 *
 * The 1.19.4 plugin had a hard-coded weighted table; the port keeps those weights and appends
 * the first wave (10), second wave (41), and third wave (30) of abstract/contraption skills.
 * {@code sky_spiral} is a movement quirk, so it has weight 0 and is never rolled by the combat table.
 */
public final class Skills {

    public static final String SKY_SPIRAL = "sky_spiral";

    public static final class Skill {
        public final String id;
        public final String display;
        public final String hint;
        /** 技能趣味小介绍（展示在 GUI 悬浮说明与 /supercow skills 指令中） */
        public final String intro;
        public final int weightNormal;
        public final int weightRage;
        public final Item icon;
        /** false = 只能在 GUI/命令里查，战斗时不会被抽到 */
        public final boolean rollable;

        Skill(String id, String display, String hint, String intro, int weightNormal, int weightRage, Item icon, boolean rollable) {
            this.id = id;
            this.display = display;
            this.hint = hint;
            this.intro = intro != null && !intro.isEmpty() ? intro : hint;
            this.weightNormal = weightNormal;
            this.weightRage = weightRage;
            this.icon = icon;
            this.rollable = rollable;
        }
    }

    private static final List<Skill> ALL = new ArrayList<>();

    static {
        // ---- original 1.19.4 roster (weights from CombatManager.kt) ----
        add("arrow", "箭矢", "主输出，命中后 35% 附加箭矢效果",
            "牛角拉弓百步穿杨，平平无奇却占了八成出勤率的劳模技能，冷不丁还会触发冰霜、雷击、爆炸、漩涡或箭雨五选一彩蛋。",
            950, 900, Items.ARROW, true);
        add("firework", "烟花", "朝目标上方发射烟花，范围伤害",
            "逢年过节气氛组担当，把五彩斑斓的礼花弹直接塞进怪堆脑门上炸开，物理超度与节日庆典一步到位。",
            15, 30, Items.FIREWORK_ROCKET, true);
        add("potion", "药水", "投掷药水，落点范围伤害 + 负面效果",
            "祖传牧场秘制黑暗料理，玻璃瓶一摔就是全套减速中毒虚弱套餐，喝过的怪物都表示再也不想路过牛棚。",
            15, 30, Items.SPLASH_POTION, true);
        add("fireball", "火球", "小火球，命中点燃（不破坏方块）",
            "牛肚子里反刍出来的浓缩沼气火球，环保不拆家，专烧怪物裤脚，熟度刚好七分。",
            5, 15, Items.FIRE_CHARGE, true);
        add("exploding_cow", "自爆牛", "发射一只会爆炸的牛，引信 30 tick",
            "艺术就是派大星——呸，是派出一只嗷嗷叫的义勇小牛直线冲锋，一秒半后轰然开花，场面极度震撼。",
            5, 10, Items.TNT, true);
        add("chicken", "轰炸鸡", "头顶刷轰炸鸡，边飞边下蛋",
            "呼叫牧场空军支援！战斗鸡群低空掠过敌阵，密集投下高爆鸡蛋地毯式洗地。",
            3, 5, Items.EGG, true);
        add("music", "音乐", "音波范围伤害 + 减速/虚弱",
            "拿出看家牛铃现场演奏死亡重金属，魔音贯耳震得方圆怪物双腿发软、脑瓜子嗡嗡作响。",
            3, 5, Items.NOTE_BLOCK, true);
        add("summon", "护卫小牛", "召唤 2~3 只小牛协战 20 秒",
            "吹响牛角号摇人！两三只热血小牛犊子破土而出围殴目标，充分诠释什么叫牛多欺负人少。",
            2, 2, Items.COW_SPAWN_EGG, true);
        add("summon_parrots", "鹦鹉", "环绕啄击的鹦鹉",
            "五彩金刚鹦鹉特攻队盘旋升空，一边学怪物惨叫一边对准脑门疯狂输出。",
            1, 2, Items.PARROT_SPAWN_EGG, true);
        add("summon_rabbits", "兔子", "冲向目标啃咬的兔子",
            "表面毛茸茸人畜无害，实则蒙蒂巨蟒同款杀手兔，蹦跳着扑上去就是一顿夺命连环啃。",
            1, 1, Items.RABBIT_SPAWN_EGG, true);

        // ---- ten abstract skills added in wave one ----
        add("cow_rain", "奶牛雨", "天上掉下几只牛，砸一下就走",
            "气象局发布极端天降正义预警：数头吨位级奶牛从云端自由落体，砸扁敌人后潇洒打卡下班。",
            8, 14, Items.SADDLE, true);
        add("milk_bomb", "牛奶炸弹", "泼一身牛奶：清除增益 + 缓慢",
            "过期三天的全脂浓稠牛奶迎面泼洒，黏糊糊挂满全身，再猛的增益Buff也得当场洗白。",
            8, 14, Items.MILK_BUCKET, true);
        add("moon_walk", "月球漫步", "宠物自己跳得又高又飘",
            "牛顿棺材板按不住了！超级牛原地开启阿波罗登月微重力模式，太空步一迈优雅升空。",
            8, 14, Items.RABBIT_FOOT, true);
        add("moo_wave", "哞叫波", "两圈音波把周围推开",
            "丹田提气一声惊天动地的『哞——』，肉眼可见的双层环形气浪把贴脸怪物全部掀翻。",
            8, 14, Items.BELL, true);
        add("hay_tornado", "干草旋风", "干草绕着目标转，把怪慢慢吸进去",
            "草垛子成精化作龙卷风暴，漫天麦秆带着怪群旋转跳跃闭着眼，越转越往风暴眼中心挤。",
            8, 14, Items.HAY_BLOCK, true);
        add("butterfly_swarm", "蝴蝶群", "围着目标乱飞乱舔",
            "名为花间蝴蝶、实为夜行蝙蝠的迷惑小分队，扑棱着翅膀糊满敌人整张脸，精神污染拉满。",
            8, 14, Items.BAT_SPAWN_EGG, true);
        add("zero_gravity", "零重力", "附近怪物一起飘起来",
            "一键拔掉方圆怪物的重力插头，全场整整齐齐化作人形气球冉冉升起，主打一个升天。",
            8, 14, Items.SHULKER_SHELL, true);
        add("mirror_cow", "镜像牛", "刷一只无敌的假牛替你挨打",
            "影分身之术！原地留下一尊刀枪不入的金牌替身假牛吸引火力，真身在旁嗑瓜子看戏。",
            8, 14, Items.NAME_TAG, true);
        add("teleport_swap", "换位", "和目标的坐标瞬间对调",
            "空间法则级整活：眼睛一眨，牛到了怪身后，怪站在了刚才挨揍的位置，防不胜防。",
            8, 14, Items.ENDER_PEARL, true);
        add("confetti", "彩纸爆炸", "一堆烟花粒子，伤害低，很吵",
            "伤害性不高但侮辱性极强！噼里啪啦炸出漫天彩纸和巨响，专门用来吓唬心脏不好的怪物。",
            8, 14, Items.GUNPOWDER, true);

        // ---- movement quirk, not part of the combat roll ----
        add(SKY_SPIRAL, "转圈飞天", "追击时偶尔把自己寻路到天上",
            "上古版本的传世名场面：寻路算法一旦突然顿悟，就会一边螺旋打转一边左脚踩右脚直冲云霄。",
            0, 0, Items.FEATHER, false);

        // =====================================================================================
        // ---- 第二弹：41 个可抽技能（全部由 QuirkSkills 实现）----
        // =====================================================================================

        // ---- 位移 8 ----
        add("moo_dash", "蛮牛冲撞", "低头蓄力后横冲过去，沿途撞飞并击退",
            "西班牙斗牛场VIP体验卡，牛角一低直接挂满档氮气加速，挡路的统统享受保龄球瓶待遇。",
            5, 10, Items.LEATHER, true);
        add("sapling_launch", "树苗弹射", "种下的树苗瞬间长大，把宠物弹上天空",
            "脚底现种橡树苗配十袋金坷垃，树木拔地而起的瞬间把整头牛像弹弓一样射向高空。",
            4, 8, Items.OAK_SAPLING, true);
        add("earth_dive", "遁地冲锋", "钻进地面从目标脚下钻出并抛起",
            "师承地鼠门派的土遁奇术，化作泥浪潜行到猎物脚底板，破土冲顶一气呵成。",
            4, 8, Items.DIRT, true);
        add("wormhole", "虫洞", "原地留下虫洞，10 tick 后瞬移回去",
            "先在原地插个空间锚点冲上去揍两拳，半秒后顺着虫洞丝滑闪回，片叶不沾身。",
            4, 8, Items.ENDER_EYE, true);
        add("grapple_rope", "套索飞爪", "射出套索，把自己拉向目标",
            "立体机动装置牧场限定版，一根拴绳甩在怪物鼻梁上，拽着几百斤的牛躯飞身骑脸。",
            3, 6, Items.LEAD, true);
        add("tumble_roll", "翻滚", "向侧面滚出去，顺便拍开路上的怪",
            "魂系老ass祖传翻滚，圆滚滚的牛肚子往侧面一滚，顺带把路边的倒霉蛋全拍飞出去。",
            3, 6, Items.RABBIT_HIDE, true);
        add("spring_step", "弹簧步", "在原地弹来弹去，弹一下就踩一下周围",
            "四只牛蹄装上了高碳钢大弹簧，原地蹦迪式连环践踏，震得地板和怪物一起抖三抖。",
            3, 6, Items.SLIME_BALL, true);
        add("meteor_jump", "陨石跳", "跳起来直接砸向目标头顶",
            "高空抛物最为致命！腾空跃起化作一颗熊熊燃烧的肥牛陨石，精准降落在敌人天灵盖。",
            3, 6, Items.MAGMA_CREAM, true);

        // ---- 召唤 7 ----
        add("bee_swarm", "蜂群", "放出一群蜜蜂，绕着刷怪点穷追不舍",
            "捅了马蜂窝战术！放出一群尾针带电的狂暴小蜜蜂，追着怪物满山跑，叮到怀疑人生。",
            4, 8, Items.HONEYCOMB, true);
        add("iron_calf", "铁犊子", "召唤一只无敌铁傀儡顶在前面",
            "有事铁大哥真上！召唤一尊浑身纯钢锻造的无敌铁犊子横刀立马，双臂一挥送怪上天。",
            3, 6, Items.IRON_INGOT, true);
        add("wolf_pack", "狼群", "三只狼从两侧包抄",
            "牧羊犬转行当打手，三匹恶狼左右两翼战术迂回包抄，专咬敌人后脚跟。",
            4, 8, Items.BONE, true);
        add("snow_golem_turret", "雪人炮台", "雪人立在宠物头顶扔雪球",
            "头顶架起一座全自动防空雪人哨戒炮，哒哒哒连珠雪球糊脸，自带物理劝退效果。",
            3, 6, Items.SNOWBALL, true);
        add("fire_cracker_chain", "鞭炮串", "串成一挂的鞭炮炸个不停",
            "过年氛围拉满的一千响大地红，顺着地面一路连环爆竹炸过去，炸得怪物原地跳脚。",
            2, 5, Items.FIREWORK_STAR, true);
        add("barn_totem", "仓鼠图腾", "插一根图腾，持续放箭",
            "就地打桩立起一根神秘牧场图腾柱，360度无死角自动索敌飙射冷箭。",
            2, 5, Items.OAK_FENCE, true);
        add("phantom_wings", "幻翼之翼", "招出一只幻翼在头顶盘旋扫射",
            "召唤熬夜冠军专属幻翼战机，在高空盘旋锁定目标后俯冲掠袭，自带夜空压迫感。",
            2, 4, Items.PHANTOM_MEMBRANE, true);

        // ---- 控制 7 ----
        add("mud_trap", "泥潭", "脚下一片泥，踩进去的怪物打滑",
            "现场和泥巴整出一片万年沼泽烂泥塘，踩进去的怪物双脚像灌了铅，寸步难行。",
            4, 8, Items.CLAY_BALL, true);
        add("ice_field", "冰封领域", "铺一层冰，怪物站不住脚",
            "液氮级冷气瞬间急冻地表，没穿防滑鞋的怪物在冰面上疯狂劈叉打滑。",
            4, 8, Items.ICE, true);
        add("sleep_beam", "催眠光波", "射一道光，打中的怪直接原地发呆",
            "发射一道死鱼眼催眠射线，被照到的怪物当场眼神清澈、大脑宕机原地罚站。",
            3, 6, Items.ENDER_EYE, true);
        add("lasso_yank", "缰绳拖拽", "套住目标往宠物这边拽",
            "牛仔很忙之反向拔河，一绳子套住逃跑的猎物脖子，硬生生拽回牛蹄子底下。",
            3, 6, Items.STRING, true);
        add("blizzard", "暴风雪", "风雪糊住视野，怪物又慢又冷",
            "人工局部特大暴风雪，鹅毛大雪夹着冰碴子往脸上刮，冻得怪物瑟瑟发抖。",
            3, 6, Items.ICE, true);
        add("quicksand", "流沙陷坑", "目标脚下一沉，往下陷",
            "脚下土地突然变成漏斗流沙坑，把四周怪物连拖带拽往坑底吸。",
            2, 5, Items.SAND, true);
        add("wind_gust", "疾风冲击", "一股狂风正面吹散怪群",
            "牛鼻孔全力喷气吹出十二级台风，迎面把扎堆的怪物吹得东倒西歪。",
            3, 6, Items.FEATHER, true);

        // ---- 爆发 6 ----
        add("dairy_volcano", "乳山喷发", "脚下喷出一道牛奶柱，范围伤害",
            "地下积累千年的沸腾热牛奶间歇泉轰然喷发，滚烫奶浪把上方敌人连续掀上天。",
            5, 10, Items.SHULKER_BOX, true);
        add("cream_mine", "奶油地雷", "埋一颗奶油地雷，踩上去就炸",
            "看似诱人的生日蛋糕，实则是高浓缩奶油诡雷，谁踩谁享受满脸裱花暴击。",
            4, 8, Items.CAKE, true);
        add("spore_bloom", "孢子爆裂", "爆开孢子云，中毒 + 视线受阻",
            "红伞伞白杆杆，炸开一团五彩毒蘑菇孢子雾，吸一口立刻躺板板。",
            3, 6, Items.RED_MUSHROOM, true);
        add("lightning_milk", "雷击牛奶", "把一道雷引到目标身上",
            "高钙牛奶导电原理实证：往敌人头顶泼奶引落九天玄雷，外焦里嫩滋滋冒烟。",
            3, 6, Items.TRIPWIRE_HOOK, true);
        add("stampede", "奔牛践踏", "一群牛从身后呼啸而过",
            "重现非洲大草原万牛奔腾名场面，铁蹄洪流从身后碾压而过，寸草不生。",
            4, 8, Items.BEEF, true);
        add("buffalo_charge", "野牛冲锋", "蓄力三连冲，最后一下最疼",
            "荒野大镖客同款疯牛三连顶，一顶比一顶猛，第三下直接把对手顶出大气层。",
            3, 6, Items.BEEF, true);

        // ---- 增益 6 ----
        add("milk_shield", "牛奶护盾", "给主人和宠物套上吸收护盾",
            "每日一杯高钙奶，强壮骨骼凝成一层金灿灿的钙质护盾，主人与爱宠同步耐揍。",
            5, 8, Items.SHIELD, true);
        add("cheese_power", "奶酪之力", "吃一口奶酪，宠物攻击力暴涨",
            "浓缩就是精华！一口陈年发酵芝士下肚，牛劲上来连自己都害怕。",
            4, 8, Items.CAKE, true);
        add("moo_roar", "哞吼", "一声吼，主人宠物一起提速",
            "战歌起！嘹亮牛吼激发肾上腺素，主人和超级牛双双开启疾跑飙车。",
            4, 8, Items.WHITE_WOOL, true);
        add("pasture_heal", "牧草愈合", "脚下一圈草，主人和宠物一起回血",
            "随地大小铺开一片呼伦贝尔翠绿牧场，吸一口青草香，伤势肉眼可见愈合。",
            4, 8, Items.WHEAT, true);
        add("sacred_bull_aura", "圣牛光环", "踩着光环，回血 + 抗性 + 发光",
            "印度神牛附体，通体绽放圣洁金光，脚踏回血抗性法阵，逼格与安全感双满。",
            3, 6, Items.GLOWSTONE_DUST, true);
        add("bull_aura", "霸体冲锋", "免疫击退，血上限临时加一截",
            "浑身肌肉硬化进入绝对霸体状态，气血暴涨，泰山崩于前而牛蹄不退半步。",
            2, 4, Items.LEATHER_HORSE_ARMOR, true);

        // ---- 趣味 7 ----
        add("milk_rain_blessing", "牛奶祝福雨", "一阵牛奶雨，淋到的怪反而变慢",
            "天降甘霖纯牛奶，主人沐浴回春，怪物淋湿后脚底打滑浑身难受。",
            3, 5, Items.MILK_BUCKET, true);
        add("cow_disguise", "伪装成牛", "变成一坨牛粪，怪物闻不到宠物",
            "战局不妙立刻改名换姓装作『一只普通奶牛』，光学迷彩一开，怪物提刀四顾心茫然。",
            3, 5, Items.BROWN_WOOL, true);
        add("moo_chorus", "哞哞合唱团", "三只牛摆开合唱，音波扫射",
            "多声部无伴奏阿卡贝拉牛棚合唱团，高音甜中音准低音直接把敌人震成内伤。",
            3, 5, Items.NOTE_BLOCK, true);
        add("herd_dance", "群牛舞", "原地跳踢踏舞，踩得周围直晃",
            "爱尔兰大河之舞牧场分舞，牛蹄高频哒哒乱跺，把周围敌人震得集体蹦迪。",
            3, 5, Items.MUSIC_DISC_CAT, true);
        add("bell_toll", "牛铃摇响", "摇响大钟，吓退一圈怪物",
            "脖子上的祖传大铜铃铛铛巨响，声波荡寇，胆小的怪物当场倒退三步。",
            4, 8, Items.BELL, true);
        add("milk_fountain", "奶泉喷涌", "脚下喷泉，周围全是奶香粒子",
            "原地化作罗马许愿池奶香喷泉，漫天飞溅的奶花把靠近的怪物淋得晕头转向。",
            2, 4, Items.MILK_BUCKET, true);
        add("piston_sheep_machine", "活塞草羊机", "刷出实体活塞草羊机，往复推刺一下子把敌人顶飞",
            "原版生电圈闻风丧胆的红石整活杰作！全自动高频往复活塞推着无辜草羊，一下子把敌人顶上九霄云外。",
            4, 8, Items.PISTON, true);

        // =====================================================================================
        // ---- 第三弹：30 个抽象整活/红石机关技能（全部由 QuirkSkills 实现，权重 2~4）----
        // =====================================================================================
        add("anvil_guillotine", "铁砧断头台", "头顶悬吊重型铁砧机关，连续三次砸扁敌人",
            "卡通片经典重物坠落桥段！在目标脑门正上方凭空架起液压铁砧机，咣当三连砸把敌人拍成二维生物。",
            3, 6, Items.ANVIL, true);
        add("sheep_rainbow_cannon", "彩虹羊大炮", "刷出七彩变色Jeb羊，喷射RGB光束轰飞敌人",
            "命名『jeb_』触发的终极电竞RGB形态！一只疯狂变色的绵羊架在炮架上，喷出辣眼睛的炫彩冲击波。",
            3, 6, Items.MAGENTA_WOOL, true);
        add("tnt_hot_potato", "烫手山芋", "刷出冒烟的烫手小牛，在怪物之间疯狂弹跳传球后炸飞",
            "击鼓传花死亡版！一只滋滋冒烟的红温小牛在怪群头顶来回横跳，音乐停在谁头上谁就原地起飞。",
            3, 6, Items.BAKED_POTATO, true);
        add("fishing_crane", "钓竿抓娃娃机", "高空降下巨型抓钩，把敌人夹到半空再松手摔下",
            "商场抓娃娃机黑幕重现：高空爪子精准夹住敌人提溜到五米高空，然后极其真实地在最高点突然松爪。",
            3, 6, Items.FISHING_ROD, true);
        add("slime_pinball", "史莱姆弹球机", "四周升起三块史莱姆弹力挡板，把敌人当弹珠来回弹射",
            "Windows经典三维弹球牧场复刻版！三只高弹史莱姆挡板围成一圈，把里面的怪物当钢珠乒乒乓乓乱弹。",
            3, 6, Items.SLIME_BLOCK, true);
        add("villager_auction", "村民拍卖会", "金牌奸商村民登场摇铃叫卖，用绿宝石雨砸懵敌人",
            "『嗯哼？胡萝卜换绿宝石！』金牌拍卖师村民现场敲槌，漫天绿宝石报价单把怪物砸得精神恍惚。",
            3, 6, Items.EMERALD, true);
        add("end_rod_drill", "末地烛钻头机", "高频旋转的末地烛推进器，突破天际把敌人钻飞",
            "草羊机的孪生兄弟！高速旋转的末地烛螺旋钻头顶着寒光一路推进，所到之处怪物纷纷捂住身后腾空而起。",
            4, 8, Items.END_ROD, true);
        add("chicken_gatling", "加特林坤坤机", "头顶悬浮全自动战斗鸡，极速连射弹幕压制",
            "练习时长两年半的防空火神炮，每秒几十发高初速弹药倾泻而出，火力密度堪比金属风暴。",
            3, 6, Items.COOKED_CHICKEN, true);
        add("boat_drift", "秋名山飞船", "召唤陆地漂移木船绕敌高速甩尾，车尾直接甩飞怪群",
            "谁说没水不能开船？五菱宏光同款秋名山木船在旱地上排水沟过弯，船尾一甩把路人甲全部撞飞。",
            3, 6, Items.OAK_BOAT, true);
        add("creeper_fakeout", "苦力怕虚晃一枪", "贴脸刷出嘶嘶膨胀的假苦力怕，最后放礼花吓飞敌人",
            "老玩家血压飙升器！一只苦力怕贴在怪物后脖颈嘶嘶作响，结果砰的一声炸出漫天爱心礼花，纯靠惊吓把怪震飞。",
            3, 6, Items.CREEPER_HEAD, true);
        add("pufferfish_minefield", "河豚刺雷阵", "四周布下四颗满气河豚刺雷，扎得敌人连跳带毒",
            "水产市场大杀器！四只气鼓鼓的满级河豚在地上乱蹦，谁靠近就给谁来一套针灸加河豚毒素。",
            3, 6, Items.PUFFERFISH, true);
        add("bed_bounce_castle", "蹦床充气堡", "把地面变成强力充气蹦床，让怪物停不下来地高空弹跳",
            "儿童乐园充气城堡搬进战场，怪物只要脚沾地就会被十倍弹力Duang地弹上天，根本没办法好好走路。",
            3, 6, Items.RED_BED, true);
        add("squid_ink_printer", "鱿鱼喷墨打印机", "空投全自动鱿鱼喷墨机，糊满敌人一脸黑墨致盲",
            "办公室坏掉的激光打印机成精！一只大鱿鱼悬空疯狂甩墨，方圆五米瞬間变成伸手不见五指的黑煤窑。",
            3, 6, Items.INK_SAC, true);
        add("golem_chiropractor", "铁傀儡正骨师", "盲人按摩刘师傅闪现身后，三记大力正骨送怪升天",
            "『忍着点，咔嚓一声就好！』铁傀儡师傅出现在怪物背后，三记势大力沉的物理正骨直接把颈椎顶上天。",
            3, 6, Items.IRON_BLOCK, true);
        add("llama_spit_turret", "羊驼口水加农炮", "左右架起双联装羊驼炮，高频喷吐生化口水击退",
            "动物园祖安双雄就位！两只高傲的羊驼左右开弓，带着浓烈气味的生化口水炮弹连环糊脸。",
            3, 6, Items.LEATHER_CHESTPLATE, true);
        add("panda_bowling", "熊猫保龄球", "召唤吨位级翻滚大熊猫直线碾压，把敌人像球瓶一样撞飞",
            "国宝级重量碾压！圆滚滚的大熊猫抱成一团化作重型保龄球直线滚过，怪群全中Strike！",
            3, 6, Items.BAMBOO, true);
        add("cat_keyboard_walker", "猫猫踩键盘", "两只猫咪在战场上乱窜乱踩，随机触发各种离谱指令效果",
            "当主子跳上程序员的机械键盘：闪电、升空、旋转、烟花随机乱放，连牛自己都不知道下一秒会发生什么。",
            3, 6, Items.COD, true);
        add("minecart_rollercoaster", "矿车过山车", "召唤脱轨狂飙的云霄飞车绕场波浪冲撞",
            "没有铁轨照样飙车！一辆不讲物理法则的过山车沿着正弦波轨迹高速盘旋，把沿途怪物全部撞飞。",
            3, 6, Items.MINECART, true);
        add("turtle_spinning_shell", "绿龟壳弹射", "发射高速旋转的马里奥绿龟壳，在敌人之间无限反弹",
            "马里奥赛车手的噩梦！一只高速自转的绿海龟在怪群之间来回折射弹跳，撞谁谁翻车。",
            3, 6, Items.SCUTE, true);
        add("fox_berry_trap", "狐狸偷菜夹", "小狐狸飞扑骑脸，撒下满地甜浆果刺丛定身放血",
            "狐狸飞扑上头一套连招，顺手在敌人脚下种满扎脚的甜浆果刺，动一下扎一下。",
            3, 6, Items.SWEET_BERRIES, true);
        add("snowman_blender", "雪人破壁料理机", "三只雪人高速旋转组成破壁机刀头，把怪搅飞出顶盖",
            "九阳破壁机牧场改装版：三尊雪人以每秒十圈的转速围绕目标狂转，把敌人搅成冰沙甩向高空。",
            3, 6, Items.CARVED_PUMPKIN, true);
        add("pig_rocket_express", "飞天猪猪外卖", "绑定窜天猴的鞍猪直线冲刺撞向目标，双双升空炸开",
            "当猪学会飞的时候，连怪物都得起飞！背绑大号窜天猴的外卖猪猪全速冲撞，带着目标一块冲上云霄。",
            3, 6, Items.CARROT_ON_A_STICK, true);
        add("witch_cauldron_soup", "女巫孟婆汤", "女巫现场支起大锅熬制冒泡毒汤，喝完忘却前尘往事",
            "『来，趁热喝了这碗孟婆汤！』女巫围着沸腾冒泡的毒雾锅疯狂加料，烫得敌人一边掉血一边乱蹦。",
            3, 6, Items.CAULDRON, true);
        add("skeleton_jazz_band", "骷髅爵士乐队", "三名冥界骷髅乐手排成一排演奏摇滚，节拍震飞全场",
            "亡灵节限定巡演！三具骷髅乐手整整齐齐抖腿甩头，每一个重低音鼓点都化作真实冲击波。",
            3, 6, Items.BONE_BLOCK, true);
        add("enderman_moving_company", "末影人搬家公司", "货拉拉末影专员瞬移上门，连续三次把敌人强行打包搬家",
            "专业拆迁搬家一条龙：末影人师傅闪现到敌人身边，不由分说拎起领子瞬移抛向半空，服务态度极佳。",
            3, 6, Items.CHORUS_FRUIT, true);
        add("polar_bear_slap", "北极熊大逼兜", "召唤西伯利亚巨熊蓄力一记大耳光，直接把敌人扇飞",
            "来自西伯利亚的呼啸寒风与清脆巴掌声！北极熊人立而起蓄力一挥，一记大逼兜把敌人扇出十米远。",
            3, 6, Items.SALMON, true);
        add("silverfish_tickle_pit", "蠹虫挠痒痒机", "四只无害小蠹虫扒在敌人脚底疯狂挠痒，笑到原地狂跳",
            "酷刑级脚底按摩！四只光滑小蠹虫钻进怪物鞋底板疯狂挠脚心，痒得怪物全程蹦跶根本停不下来。",
            3, 6, Items.STONE_BRICKS, true);
        add("dolphin_surfboard", "海豚冲浪板", "海豚踏着巨型水花浪潮横扫战场，把敌人卷上浪尖",
            "阳光沙滩海浪仙人掌！一只快乐海豚掀起陆地海啸冲浪而过，把整排怪物高高拍在浪尖上。",
            3, 6, Items.HEART_OF_THE_SEA, true);
        add("mooshroom_nuclear_stew", "哞菇浓汤高压锅", "红温哞菇高压锅读秒充能，表盘爆表后炸出蘑菇云",
            "厨房安全事故现场！一只红哞菇在原地嗡嗡充能升温，压力表读到100%瞬间轰出漫天浓汤蘑菇云。",
            3, 6, Items.MUSHROOM_STEW, true);
        add("bat_ufo_abduction", "蝙蝠外星飞碟", "高空蝙蝠编队投下外星牵引光束，把敌人吸往太空",
            "第三类接触实录！夜蝠编队在头顶盘旋成UFO飞碟，一道笔直的白色牵引光柱把地面怪物缓缓吸上天际。",
            3, 6, Items.END_CRYSTAL, true);
        // Wave four: bounded visual contraptions, with distinct motion/control profiles.
        add("slime_trampoline", "史莱姆弹簧床", "软垫反复弹跳，短促托起近处怪物",
            "谁把史莱姆铺成床？绿色软垫上下弹三回，只负责晃晕怪物，不负责售后。", 2, 3, Items.SLIME_BLOCK, true);
        add("magnet_milk", "磁铁牛奶", "奶桶磁场聚拢怪物并短暂减速",
            "奶里是不是掺了磁铁？三个奶桶绕圈转，把附近怪物往桶心轻轻拽。", 2, 3, Items.BUCKET, true);
        add("redstone_disco", "红石迪斯科", "红石灯球跳方阵，节拍中附带短暂虚弱",
            "无需电路也能蹦迪！红石投影踩着方格舞步闪动，怪物跟错拍就会短暂手软。", 2, 3, Items.REDSTONE, true);
        add("paper_airplane", "纸飞机快递", "纸飞机扇形滑翔，轻推投递路线附近怪物",
            "本次快递没有签收码，只有三架纸飞机轮番滑过，在落点盖个轻轻的章。", 2, 3, Items.PAPER, true);
        add("cheese_meteor", "芝士陨石", "芝士块螺旋下落，落点轻震",
            "天上掉下来的不是馅饼，而是融化的芝士陨石；只有投影，没有真砸坏的地板。", 2, 3, Items.YELLOW_DYE, true);
        add("umbrella_spin", "雨伞大风车", "蘑菇伞盘旋转动，横向扫开近处怪物",
            "晴天也要撑伞！巨型蘑菇伞沿圆盘转圈，把想蹭伞的怪物轻轻扫开。", 2, 3, Items.BROWN_MUSHROOM, true);
        add("clockwork_carrot", "发条胡萝卜", "胡萝卜按齿轮轨迹蹦跳，短暂减速",
            "拧上发条就开始加班，胡萝卜沿齿轮节拍蹦跶，怪物却跟不上蔬菜的工时。", 2, 3, Items.CARROT, true);
        add("dispenser_popcorn", "发射器爆米花", "发射器投影喷出弧线爆米花，轻微顶起",
            "没有红石、没有真弹药，只有抛物线爆米花；看电影前先给怪物一点热身。", 2, 3, Items.DISPENSER, true);
        add("rubber_duck_fleet", "橡皮鸭舰队", "三只鸭形投影蛇形巡航，横向轻推",
            "浴缸舰队申请出征！小黄鸭不下真水，沿波浪队列摇摆着驶过战场。", 2, 3, Items.EGG, true);
        add("tofu_guillotine", "豆腐切切机", "豆腐刀架轮流落下，产生短暂虚弱",
            "豆腐车间认真开工，三道软绵绵的白色刀影轮流落下，威风比伤害更大。", 2, 3, Items.WHITE_WOOL, true);
        add("gravity_yo_yo", "重力悠悠球", "悠悠球上下往返，交替轻抬和回拉",
            "抛出去又收回来，重力也被当成玩具；怪物体验的只是轻量版上上下下。", 2, 3, Items.ENDER_PEARL, true);
        add("milk_tea_tornado", "奶茶龙卷风", "奶茶杯螺旋升高，轻拉附近怪物",
            "少糖去冰加旋风！奶茶投影绕成小龙卷，怪物只能排队，不能免费续杯。", 2, 3, Items.MILK_BUCKET, true);
        add("bell_metronome", "钟声节拍器", "钟摆按节拍左右摆动，轻推并短暂减速",
            "牧场音乐课开课啦！钟摆准点扫过，怪物节奏不稳就只好暂时慢半拍。", 2, 3, Items.BELL, true);
        add("pumpkin_bowling", "南瓜保龄球", "南瓜沿地面滚过，侧向撞开怪物",
            "没有球道也要打保龄球，南瓜贴地滚动；怪物不是瓶子，撞完仍能好好站着。", 2, 3, Items.PUMPKIN, true);
        add("bread_frisbee", "面包飞盘", "面包沿回旋镖轨迹折返，轻推两侧",
            "面包不落地，直接当飞盘！绕个弯再回来，顺路给怪物递一份战斗早餐。", 2, 3, Items.BREAD, true);
        add("fishing_moon", "钓一轮月亮", "钓竿挂起月亮光点，轻轻上提附近怪物",
            "今天不钓鱼，改钓月亮；夜色只是粒子表演，不会把怪物传送上天。", 2, 3, Items.FISHING_ROD, true);
        add("bookshelf_domino", "书架多米诺", "书架投影依次倾倒，附带短暂虚弱",
            "知识就是力量，但这次知识先排队倒下；书架仅是投影，不拆谁家的图书馆。", 2, 3, Items.BOOKSHELF, true);
        add("potato_satellite", "土豆卫星", "三颗土豆沿倾斜轨道环绕，向外轻推",
            "农业航天计划正式升空！土豆卫星绕着目标转，轨道很认真，载荷很抽象。", 2, 3, Items.POTATO, true);
        add("carpet_magic_show", "地毯魔术秀", "飞毯波浪升降，短暂轻抬并减速",
            "地毯不负责载客，只负责表演；升降波纹经过时，怪物会觉得脚底有点飘。", 2, 3, Items.PURPLE_CARPET, true);
        add("watermelon_catapult", "西瓜投石机", "西瓜按抛物线落下，落点轻推",
            "瓜熟了就该上投石机？三枚投影西瓜抛进落点，地面完好，怪物稍微挪步。", 2, 3, Items.MELON, true);
        add("lantern_jellyfish", "灯笼水母", "灯笼水母伸缩漂游，轻拉并短暂虚弱",
            "水母忘带海水，干脆提着灯笼来上班；触须是光点，不是会蜇人的真实生物。", 2, 3, Items.LANTERN, true);
        add("hopper_vacuum", "漏斗吸尘器", "漏斗漏斗形旋转，轻吸怪物并短暂减速",
            "牧场大扫除，把怪物当灰尘轻轻吸拢；不吸玩家，也不偷物品。", 2, 3, Items.HOPPER, true);
        add("cake_conveyor", "蛋糕传送带", "蛋糕方阵循环平移，轻推沿线怪物",
            "自动化甜品线开始运行，蛋糕排队经过；没有真实传送带，也不会搬走方块。", 2, 3, Items.CAKE, true);
        add("amethyst_orchestra", "紫晶交响乐", "紫色晶点按和弦摆动，附带短暂虚弱",
            "紫色染料客串晶石乐器，奏一小段不扰民的交响乐；旧版本也能听个热闹。", 2, 3, Items.PURPLE_DYE, true);
        add("sock_black_hole", "袜子黑洞", "黑袜绕成收缩旋涡，轻拉近处怪物",
            "洗衣机失踪的袜子终于找到了，原来在这里转成小黑洞；吸力有限，绝不吞世界。", 2, 3, Items.BLACK_WOOL, true);
    }

    private static void add(String id, String display, String hint, String intro, int wn, int wr, Item icon, boolean rollable) {
        ALL.add(new Skill(id, display, hint, intro, wn, wr, icon, rollable));
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
