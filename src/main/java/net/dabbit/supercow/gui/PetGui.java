package net.dabbit.supercow.gui;

import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.combat.CombatManager.PetMode;
import net.dabbit.supercow.combat.Skills;
import net.dabbit.supercow.combat.SummonManager.SummonMode;
import net.dabbit.supercow.config.SuperCowConfig;
import net.dabbit.supercow.data.PetData;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The pet "chest GUI": a vanilla 6-row chest that shows the pet status and lets the owner toggle
 * every skill on/off. It is a plain chest menu on the wire, so an unmodded client can open it.
 *
 * Every click is answered by {@link PetMenu#clicked} and then cancelled; the menu never lets items
 * move, it only re-renders itself and pushes the new contents back to the client.
 */
public final class PetGui {

    public static final int SIZE = 54;
    /**
     * The carried stack the client believes it holds after the click currently being processed.
     * Published by {@code ServerGamePacketListenerMixin} right before vanilla handles the packet.
     */
    private static final ThreadLocal<ItemStack> PREDICTED_CARRIED = new ThreadLocal<ItemStack>();

    public static void setPredictedCarried(ItemStack stack) {
        PREDICTED_CARRIED.set(stack);
    }

    private static ItemStack takePredictedCarried(ServerPlayer player) {
        ItemStack predicted = PREDICTED_CARRIED.get();
        PREDICTED_CARRIED.remove();
        return predicted != null ? predicted : player.inventory.getCarried();
    }


    private static final int SKILL_START = 9;
    private static final int SLOT_MODE = 4;
    private static final int SLOT_SUMMON_MODE = 5;
    private static final int SLOT_QUIRK = 6;
    private static final int SLOT_FOLLOW = 7;
    private static final int SLOT_REFRESH = 8;
    private static final int SLOT_TARGET = 43;
    private static final int SLOT_COOLDOWNS = 44;
    private static final int SLOT_HEAL = 36;
    private static final int SLOT_RECALL = 37;
    private static final int SLOT_CALL = 38;
    private static final int SLOT_ALL_ON = 39;
    private static final int SLOT_ALL_OFF = 40;
    private static final int SLOT_PREV_PAGE = 41;
    private static final int SLOT_NEXT_PAGE = 42;
    private static final int SLOT_CLOSE = 53;
    private static final String[] INFO_KEYS = {
        "宠物信息", "技能开关数量", "宠物状态", "使用说明"
    };

    /** Skill slots per page: the 27 slots of rows 2..4, between the info row and the action row. */
    public static final int SKILLS_PER_PAGE = 27;

    /** Total number of skill pages for the current roster (always at least one). */
    public static int pageCount() {
        int size = Skills.all().size();
        return Math.max(1, (size + SKILLS_PER_PAGE - 1) / SKILLS_PER_PAGE);
    }

    /** Wraps a page index into [0, pageCount()). */
    public static int clampPage(int page) {
        int pages = pageCount();
        if (page < 0) {
            return pages - 1;
        }
        return page >= pages ? 0 : page;
    }

    public static final double FOLLOW_MIN = 2.0D;
    public static final double FOLLOW_MAX = 24.0D;

    /**
     * Moves the page of an already-open panel from the command layer, e.g. /supercow gui 2.
     * Returns false when the viewer does not have our menu open.
     */
    public static boolean setPage(ServerPlayer player, int page) {
        if (player == null || !(player.containerMenu instanceof PetMenu)) {
            return false;
        }
        PetMenu menu = (PetMenu) player.containerMenu;
        menu.skillPage = clampPage(page);
        menu.refresh();
        menu.pushContents(player);
        return true;
    }

    public static double clampFollow(double value) {
        if (Double.isNaN(value)) {
            return 20.0D;
        }
        return Math.max(FOLLOW_MIN, Math.min(FOLLOW_MAX, Math.round(value)));
    }

    public static String followRangeLabel(SuperCowConfig cfg) {
        double d = cfg.followDistance;
        if (d <= 4.0D) {
            return "§a近";
        }
        if (d <= 11.0D) {
            return "§e中";
        }
        return "§6远";
    }

    private PetGui() {
    }

    public static void open(ServerPlayer player, Cow pet, UUID ownerUuid) {
        open(player, pet, ownerUuid, 0);
    }

    /** Opens the panel on the given 0-based skill page. */
    public static void open(ServerPlayer player, Cow pet, UUID ownerUuid, final int page) {
        SimpleContainer container = new SimpleContainer(SIZE);
        final int startPage = clampPage(page);
        player.openMenu(new SimpleMenuProvider(
            (syncId, inv, p) -> {
                PetMenu menu = new PetMenu(syncId, inv, container, pet.getUUID(), ownerUuid,
                    p instanceof ServerPlayer ? (ServerPlayer) p : player);
                menu.skillPage = startPage;
                menu.refresh();
                return menu;
            },
            new TextComponent("§6§l超级牛 · 面板")));
    }

    public static final class PetMenu extends ChestMenu {
        private boolean configDirty;
        private boolean refreshing;
        private long lastRefreshTick = Long.MIN_VALUE;
        private long closeRequestedTick = Long.MIN_VALUE;
        private String lastFeedback = "§7点击按钮后在这里查看操作结果";
        private final SimpleContainer container;
        private final UUID petUuid;
        private final UUID ownerUuid;
        private final ServerPlayer viewer;

        public PetMenu(int syncId, Inventory playerInventory, SimpleContainer container, UUID petUuid, UUID ownerUuid,
                       ServerPlayer viewer) {
            super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
            this.container = container;
            this.petUuid = petUuid;
            this.ownerUuid = ownerUuid;
            this.viewer = viewer;
            refresh();
        }

        @Override
        public boolean stillValid(Player player) {
            if (!ownerUuid.equals(player.getUUID())) {
                return false;
            }
            Entity pet = petEntity(player, petUuid);
            return pet instanceof Cow && pet.isAlive(); // Owner control panel remains usable while the pet moves.
        }

        /** ServerPlayer calls this every tick; only rebuild live lore once per second. */
        @Override
        public void broadcastChanges() {
            if (refreshing) {
                return;
            }
            SuperCowMod mod = SuperCowMod.getInstance();
            if (container != null && viewer != null && viewer.containerMenu == this && mod != null) {
                long now = mod.getServerTicks();
                if (closeRequestedTick != Long.MIN_VALUE && now > closeRequestedTick) {
                    viewer.closeContainer();
                    return;
                }
                if (lastRefreshTick == Long.MIN_VALUE || now - lastRefreshTick >= 20L) {
                    refresh();
                }
            }
            // Vanilla compares stacks before sending changed slots, preserving the carried stack.
            super.broadcastChanges();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        /**
         * FIXED BUG: the original only reacted to ClickType.PICKUP and always reported an empty
         * carried stack. Shift-clicks, drags and double-clicks then fell through to the vanilla
         * container logic, which "moved" the icons into the player inventory on the client while
         * the server kept them in the panel - i.e. ghost items.
         *
         * Now every click type is swallowed on the panel side and the real carried stack is
         * returned, so vanilla's prediction check (ItemStack.matches against the packet) fails
         * and the client gets a full resync from us instead of inventing items.
         */
        /**
         * Every click is accepted rather than rejected.
         *
         * <p>1.16.5's ServerGamePacketListenerImpl.handleContainerClick compares the stack we
         * return with the stack the client predicted. On a mismatch it sends a "rejected" ack,
         * calls setSynched(player, false) and re-pushes the window. Real clients react badly to
         * that while the mouse button is still held: the cursor prediction is re-applied locally,
         * the icon visually lands in the player inventory, and (when the rejection races the
         * auto-close below) the same window stops accepting clicks entirely.</p>
         *
         * <p>So we mirror the client's prediction exactly: the carried stack the client sent back
         * is what we return. The client prediction for "pick up an icon" is therefore confirmed,
         * the panel content is re-pushed immediately afterwards (which resets the cursor and every
         * slot), and no desync/synch churn ever happens.</p>
         */
        @Override
        public ItemStack clicked(int slotId, int button, ClickType type, Player player) {
            if (!(player instanceof ServerPlayer)) {
                return ItemStack.EMPTY;
            }
            ServerPlayer sp = (ServerPlayer) player;
            if (sp.containerMenu != this) {
                return sp.inventory.getCarried();
            }
            ItemStack predicted = takePredictedCarried(sp);
            if (!ownerUuid.equals(sp.getUUID())) {
                pushContents(sp);
                return predicted;
            }
            if (slotId >= 0 && slotId < SIZE) {
                if ((type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) && (button == 0 || button == 1)) {
                    handleClick(slotId, button, sp);
                }
                if (sp.containerMenu == this) {
                    refresh();
                    pushContents(sp);
                }
                return predicted;
            }
            // Player-inventory half and clicks outside the window: nothing may move, so the
            // prediction is confirmed and the client is resynced from the authoritative stacks.
            if (sp.containerMenu == this) {
                pushContents(sp);
            }
            return predicted;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (configDirty && SuperCowMod.getInstance() != null) {
                SuperCowMod.getInstance().saveConfigFile();
                configDirty = false;
            }
        }

        // ------------------------------------------------------------ rendering

        private void refresh() {
            // SimpleContainer notifies the menu through slotsChanged/broadcastChanges for every
            // setItem. Rebuilding 54 slots would otherwise push half-baked packets to the client
            // (icons flickering / appearing one click late), so writes are made silent and the
            // finished panel is pushed once at the end.
            if (refreshing) {
                return;
            }
            refreshing = true;
            try {
                renderPanel();
                lastRefreshTick = SuperCowMod.getInstance().getServerTicks();
            } finally {
                refreshing = false;
            }
        }

        /** 0-based page of the skill grid currently rendered for this viewer. */
        private int skillPage;

        private void renderPanel() {
            SuperCowMod mod = SuperCowMod.getInstance();
            SuperCowConfig cfg = mod.getConfig();
            PetData data = mod.getPetData(ownerUuid);
            Entity entity = containerLevelEntity();
            Cow pet = entity instanceof Cow ? (Cow) entity : null;
            LivingEntity target = mod.getCombatManager().getCurrentTarget(ownerUuid);
            boolean raging = mod.getCombatManager().isInRageMode(ownerUuid);
            String currentSkill = mod.getCombatManager().getCurrentSkillId(ownerUuid);
            if (pet != null && mod.getPathfindingManager().isSpiraling(pet.getUUID())) {
                currentSkill = Skills.SKY_SPIRAL;
            }
            double health = pet != null ? pet.getHealth() : data != null ? data.currentHealth : 0.0D;
            double maxHealth = pet != null ? pet.getMaxHealth() : data != null ? data.maxHealth : 0.0D;

            clear();
            for (int i = 0; i < SIZE; i++) {
                container.setItem(i, filler());
            }

            String levelText = data != null ? ("Lv." + data.level) : "无宠物";
            List<String> info = new ArrayList<>();
            if (data != null) {
                info.add("§7生命：§f" + decimal(health) + " / " + decimal(maxHealth));
                info.add("§7经验：§f" + data.exp + " / " + mod.expNeeded(data.level));
                info.add("§7累计击杀：§f" + data.totalKills);
                info.add("§7状态：§f" + (data.isAlive ? "存活" : "阵亡（等待复活）"));
                info.add("§7战斗模式：§f" + PetMode.fromString(data.combatMode).getDisplayName());
                info.add("§7召唤模式：§f" + SummonMode.fromString(data.summonMode).getDisplayName());
            } else {
                info.add("§7还没有宠物牛");
            }
            List<String> summary = new ArrayList<>(info);
            summary.add("§7主人：§f" + viewer.getName().getString());
            summary.add("§7主人 ↔ 牛：§f" + distanceLabel(viewer, pet));
            summary.add("§8生命优先使用实体实时值；未加载时使用记录值");
            summary.add("§8点击刷新 · 打开期间每20 tick自动更新");
            container.setItem(0, make(Items.COW_SPAWN_EGG, "§6§l超级牛 §e" + levelText, summary));

            int enabled = 0;
            for (Skills.Skill skill : Skills.all()) {
                if (cfg.isSkillEnabled(skill.id)) {
                    enabled++;
                }
            }
            container.setItem(1, make(Items.EXPERIENCE_BOTTLE, "§a技能开关 §f" + enabled + "/" + Skills.all().size(),
                java.util.Arrays.asList("§7已启用：§f" + enabled + " / " + Skills.all().size(),
                    "§7左键 = 全部开启", "§7右键 = 全部关闭",
                    "§7共 " + pageCount() + " 页，每页 " + SKILLS_PER_PAGE + " 个")));
            container.setItem(2, make(Items.COMPASS, "§d位置与距离",
                java.util.Arrays.asList(
                    "§7主人 ↔ 牛：§f" + distanceLabel(viewer, pet),
                    "§7主人维度：§f" + dimensionLabel(viewer),
                    "§7牛的维度：§f" + dimensionLabel(pet),
                    "§7牛的坐标：§f" + positionLabel(pet),
                    "§7主人坐标：§f" + positionLabel(viewer),
                    "§7实体状态：" + (pet != null && pet.isAlive() ? "§a已加载 / 存活" : "§7未加载或已离场"),
                    "§8不同维度不计算直线距离；点击刷新")));
            container.setItem(3, make(Items.PAPER, "§e使用说明 §8(只读)",
                java.util.Arrays.asList("§7连续右键自己的牛三次 = 打开面板", "§7手里拿干草块右键 = 喂食",
                    "§7顶部：信息 / 技能总开关 / 位置 / 模式 / 跟随 / 刷新",
                    "§7第二~四排：左键切换技能，右键关闭",
                    "§7操作排：治疗 / 收回 / 召回身边 / 全开 / 全关 / 翻页",
                    "§7目标和冷却图标：点击手动刷新详情",
                    "§8距离、生命和目标每20 tick自动刷新，无需重开")));

            container.setItem(SLOT_MODE, make(Items.SHIELD,
                "§b战斗模式 §f" + (data != null ? PetMode.fromString(data.combatMode).getDisplayName() : "-"),
                java.util.Arrays.asList("§7当前：§f" + (data != null ? PetMode.fromString(data.combatMode).getDisplayName() : "-"),
                    "§7狂暴状态：" + (raging ? "§c生效中" : "§7未生效"),
                    "§7当前目标：§f" + (target != null ? target.getName().getString() : "无"),
                    "§7点击切换")));
            container.setItem(SLOT_SUMMON_MODE, make(Items.WHEAT,
                "§b召唤模式 §f" + (data != null ? SummonMode.fromString(data.summonMode).getDisplayName() : "-"),
                java.util.Arrays.asList("§7当前：§f" + (data != null ? SummonMode.fromString(data.summonMode).getDisplayName() : "-"),
                    "§7点击切换")));
            container.setItem(SLOT_QUIRK, make(Items.BREWING_STAND,
                "§d技能风格 §f" + net.dabbit.supercow.combat.Quirks.describe(),
                java.util.Arrays.asList("§7当前：§f" + net.dabbit.supercow.combat.Quirks.describe(),
                    "§7safe=全修复 / mixed=混合 / chaos=全BUG", "§7点击循环切换")));

            container.setItem(SLOT_FOLLOW, make(Items.LEAD,
                "§b跟随范围 §f" + followRangeLabel(cfg),
                java.util.Arrays.asList(
                    "§7当前主人 ↔ 牛：§f" + distanceLabel(viewer, pet),
                    "§f" + String.format("%.1f", cfg.followDistance) + "§7 格：牛停在离你这么远的地方",
                    "§8左键 = 更远 / 右键 = 更近（2 ~ 24 格）",
                    "§8远一点，牛走路的声音就离你远一点",
                    "§8指令：/supercow follow <格数>")));

            List<Skills.Skill> skills = Skills.all();
            int pages = pageCount();
            skillPage = clampPage(skillPage);
            int firstSkill = skillPage * SKILLS_PER_PAGE;
            // Only render the current page: 27 slots, so the grid no longer silently drops the
            // skills past SLOT_HEAL once the roster grows.
            for (int i = 0; i < SKILLS_PER_PAGE; i++) {
                int index = firstSkill + i;
                int slot = SKILL_START + i;
                if (index >= skills.size()) {
                    container.setItem(slot, make(Items.GRAY_STAINED_GLASS_PANE, "§8空白技能位",
                        java.util.Collections.singletonList("§8本页没有更多技能")));
                    continue;
                }
                Skills.Skill skill = skills.get(index);
                boolean on = cfg.isSkillEnabled(skill.id);
                List<String> lore = new ArrayList<>();
                for (String line : wrapLore(skill.hint, 22)) {
                    lore.add("§7" + line);
                }
                if (skill.intro != null && !skill.intro.isEmpty() && !skill.intro.equals(skill.hint)) {
                    for (String line : wrapLore(skill.intro, 22)) {
                        lore.add("§6▸ §f" + line);
                    }
                }
                if (skill.rollable) {
                    lore.add("§8权重：普通 " + skill.weightNormal + " / 狂暴 " + skill.weightRage);
                } else {
                    lore.add("§8不参与随机抽卡");
                }
                lore.add(on ? "§a状态：开启 §8(点击关闭)" : "§c状态：关闭 §8(点击开启)");
                lore.add("§8ID: " + skill.id);
                lore.add("§8序号 " + (index + 1) + "/" + skills.size() + " · 第" + (skillPage + 1) + "/" + pages + "页");
                container.setItem(slot, make(on ? Items.LIME_DYE : Items.GRAY_DYE,
                    (on ? "§a✔ " : "§7✘ ") + skill.display, lore));
            }
            // Page arrows live on the action row so the whole grid stays clickable.
            container.setItem(SLOT_PREV_PAGE, make(Items.ARROW,
                "§e← 上一页 §8(" + (skillPage + 1) + "/" + pages + ")",
                java.util.Arrays.asList("§7每页 " + SKILLS_PER_PAGE + " 个技能",
                    "§7Sneak + 左键 = 跳到第一页",
                    "§8可用 /supercow gui <页码> 直接跳转")));
            container.setItem(SLOT_NEXT_PAGE, make(Items.ARROW,
                "§e下一页 → §8(" + (skillPage + 1) + "/" + pages + ")",
                java.util.Arrays.asList("§7每页 " + SKILLS_PER_PAGE + " 个技能",
                    "§7Sneak + 左键 = 跳到最后一页",
                    "§8可用 /supercow gui <页码> 直接跳转")));

            container.setItem(SLOT_REFRESH, make(Items.CLOCK, "§b刷新面板",
                java.util.Arrays.asList("§7点击立即更新距离、生命、目标与冷却说明",
                    "§7自动刷新：每20 tick（正常约1秒）",
                    "§7保持当前技能页，不改变技能与战斗模式")));
            List<String> targetLore = new ArrayList<>();
            targetLore.add("§7目标：§f" + (target != null ? target.getName().getString() : "无（跟随 / 待命）"));
            if (target != null) {
                targetLore.add("§7目标生命：§f" + decimal(target.getHealth()) + " / " + decimal(target.getMaxHealth()));
                targetLore.add("§7牛 ↔ 目标：§f" + distanceLabel(pet, target));
                targetLore.add("§7主人 ↔ 目标：§f" + distanceLabel(viewer, target));
                targetLore.add("§7目标维度：§f" + dimensionLabel(target));
            }
            targetLore.add("§8点击刷新（只读，不强制切换目标）");
            container.setItem(SLOT_TARGET, make(Items.BOW, "§c战斗目标", targetLore));
            container.setItem(SLOT_COOLDOWNS, make(Items.CLOCK, "§e冷却与战斗节奏",
                java.util.Arrays.asList(
                    "§7当前/最近释放：§b" + (currentSkill == null ? "无（待命）" : Skills.displayOf(currentSkill)),
                    "§7普攻剩余冷却：§f" + seconds(mod.getCombatManager().getAttackCooldownRemainingTicks(ownerUuid)),
                    "§7普攻基准间隔：§f" + seconds(Math.max(11, raging ? cfg.rageAttackCooldownTicks : cfg.attackCooldownTicks)),
                    "§7随机浮动：§f±" + Math.max(0, Math.min(20, cfg.attackIntervalJitterTicks)) + " tick（两tick粒度）",
                    "§7高血量Boss：§f更慢普攻、更多技能",
                    "§7特殊攻击间隔：§f" + seconds(cfg.specialAttackCooldownTicks),
                    "§7召唤间隔：§f" + seconds(cfg.summonCooldownTicks),
                    "§7喂食间隔：§f" + seconds(cfg.feedCooldownTicks),
                    "§7狂暴：" + (raging ? "§c生效中" : "§7未生效"),
                    "§8剩余冷却为实测截止时间；以上为基准配置",
                    "§8技能名短暂展示最近释放；点击刷新")));
            container.setItem(49, make(Items.PAPER, "§f最近操作",
                java.util.Arrays.asList(lastFeedback, "§8操作结果保留在此，刷新不会清除")));
            container.setItem(SLOT_HEAL, make(Items.GOLDEN_CARROT, "§a治疗宠物",
                java.util.Arrays.asList("§7当前生命：§f" + decimal(health) + " / " + decimal(maxHealth), "§7点击把生命补满")));
            container.setItem(SLOT_RECALL, make(Items.LEAD, "§e收回宠物", java.util.Collections.singletonList("§7宠物回到待命")));
            container.setItem(SLOT_CALL, make(Items.ENDER_EYE, "§e召回到身边",
                java.util.Arrays.asList("§7主人 ↔ 牛：§f" + distanceLabel(viewer, pet),
                    "§7点击把当前宠物传送到你脚下", "§8不会重新生成宠物或改变战斗模式")));
            container.setItem(SLOT_ALL_ON, make(Items.LIME_DYE, "§a全部开启", java.util.Collections.singletonList("§7所有技能重新启用")));
            container.setItem(SLOT_ALL_OFF, make(Items.GRAY_DYE, "§c全部关闭", java.util.Collections.singletonList("§7只保留近战和自动索敌")));
            container.setItem(SLOT_CLOSE, make(Items.BARRIER, "§c关闭", java.util.Collections.singletonList("§7也可以直接按 ESC")));
        }

        private static String decimal(double value) {
            return String.format(java.util.Locale.ROOT, "%.1f", value);
        }

        private static String seconds(int ticks) {
            return decimal(Math.max(0, ticks) / 20.0D) + " 秒";
        }

        private static String distanceLabel(Entity from, Entity to) {
            if (from == null || to == null) {
                return "不可用（实体未加载）";
            }
            if (from.level != to.level) {
                return "不同维度（不可比较）";
            }
            return decimal(Math.sqrt(from.distanceToSqr(to))) + " 格";
        }

        private static String dimensionLabel(Entity entity) {
            return entity != null ? entity.level.dimension().location().toString() : "未知（实体未加载）";
        }

        private static String positionLabel(Entity entity) {
            return entity != null ? String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f",
                entity.getX(), entity.getY(), entity.getZ()) : "未知（实体未加载）";
        }

        private Entity containerLevelEntity() {
            return petEntity(viewer, petUuid);
        }

        /**
         * 1.16.5 only has ServerLevel#getEntity(UUID), which returns null for an unloaded chunk.
         * A null here used to make stillValid() flap, and ServerPlayer.tick() reacts to that by
         * calling closeContainer(): the server closes the menu and sends a container-close packet,
         * but a real client whose mouse is still "holding" the window keeps its chest open with a
         * stale window id, so every following click was silently dropped ("所有东西都点不了").
         * The mod's own registry is authoritative, so fall back to it instead of flapping.
         */
        private static Entity petEntity(Player player, UUID petUuid) {
            if (player == null || !(player.level instanceof ServerLevel)) {
                return null;
            }
            Entity byId = ((ServerLevel) player.level).getEntity(petUuid);
            if (byId != null) {
                return byId;
            }
            SuperCowMod mod = SuperCowMod.getInstance();
            if (mod == null) {
                return null;
            }
            for (UUID owner : new ArrayList<UUID>(mod.getPetOwnerUuids())) {
                Cow pet = mod.getActivePet(owner);
                if (pet != null && petUuid.equals(pet.getUUID())) {
                    return pet;
                }
            }
            return null;
        }

        private void clear() {
            for (int i = 0; i < SIZE; i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
        }

        // ------------------------------------------------------------ click handling

        private void handleClick(int slot, int button, ServerPlayer player) {
            SuperCowMod mod = SuperCowMod.getInstance();
            SuperCowConfig cfg = mod.getConfig();
            PetData data = mod.getPetData(ownerUuid);
            List<Skills.Skill> skills = Skills.all();

            int skillIndex = slot >= SKILL_START && slot < SKILL_START + SKILLS_PER_PAGE
                ? skillPage * SKILLS_PER_PAGE + (slot - SKILL_START) : -1;
            if ((skillIndex >= 0 && skillIndex < skills.size() || slot == 1 || slot == SLOT_ALL_ON
                || slot == SLOT_ALL_OFF || slot == SLOT_QUIRK || slot == SLOT_FOLLOW) && !player.hasPermissions(2)) {
                tell(player, "§c这是全服共享设置，需要管理员权限；个人模式和召回仍可使用");
                return;
            }
            if (skillIndex >= 0 && skillIndex < skills.size()) {
                Skills.Skill skill = skills.get(skillIndex);
                // Left click toggles, right click forces "off" (== a clear "this one is disabled").
                boolean now = button == 1 ? false : !cfg.isSkillEnabled(skill.id);
                cfg.setSkillEnabled(skill.id, now);
                configDirty = true;
                tell(player, "技能 §f" + skill.display + (now ? " §a开启" : " §c关闭"));
                return;
            }

            if (slot == SLOT_REFRESH || slot == SLOT_TARGET || slot == SLOT_COOLDOWNS) {
                tell(player, "§a已刷新面板，悬停物品查看最新状态");
                return;
            }

            // ---- first row: every icon does something ----
            if (slot <= 3) {
                if (slot == 1) {
                    boolean allOn = button != 1;
                    for (Skills.Skill skill : Skills.all()) {
                        cfg.setSkillEnabled(skill.id, allOn);
                    }
                    configDirty = true;
                    tell(player, allOn ? "§a已开启全部技能" : "§c已关闭全部技能");
                } else if (slot == 0) {
                    int on = 0;
                    for (Skills.Skill skill : Skills.all()) {
                        if (cfg.isSkillEnabled(skill.id)) {
                            on++;
                        }
                    }
                    tell(player, "§6Lv." + (data != null ? data.level : 0) + " §f"
                        + String.format("%.1f", data != null ? data.currentHealth : 0.0D) + "/"
                        + String.format("%.1f", data != null ? data.maxHealth : 0.0D) + " HP §7技能 "
                        + on + "/" + Skills.all().size());
                } else if (slot == 2) {
                    Entity petEntity = petEntity(player, petUuid);
                    tell(player, petEntity != null
                        ? "§a宠物在 §f" + String.format("%.0f, %.0f, %.0f", petEntity.getX(), petEntity.getY(), petEntity.getZ())
                        : "§c宠物不在此世界");
                } else {
                    tell(player, "§7说明页：鼠标悬停看说明，其他格子可点击");
                }
                return;
            }
            if (data == null) {
                return;
            }
            if (slot == SLOT_MODE) {
                PetMode next = nextMode(PetMode.fromString(data.combatMode));
                data.combatMode = next.name();
                mod.getCombatManager().setPetMode(ownerUuid, next);
                tell(player, "§b战斗模式 §f" + next.getDisplayName());
            } else if (slot == SLOT_SUMMON_MODE) {
                SummonMode next = nextSummonMode(SummonMode.fromString(data.summonMode));
                data.summonMode = next.name();
                mod.getSummonManager().setSummonMode(ownerUuid, next);
                tell(player, "§b召唤模式 §f" + next.getDisplayName());
            } else if (slot == SLOT_QUIRK) {
                String mode = cfg.quirkMode != null ? cfg.quirkMode.toLowerCase() : "mixed";
                String next = mode.startsWith("s") ? "mixed" : (mode.startsWith("m") ? "chaos" : "safe");
                cfg.legacyQuirksEnabled = true;
                cfg.quirkMode = next;
                configDirty = true;
                tell(player, "§d技能风格 §f" + net.dabbit.supercow.combat.Quirks.describe());
            } else if (slot == SLOT_FOLLOW) {
                double step = button == 1 ? -1.0D : 1.0D;
                cfg.followDistance = clampFollow(cfg.followDistance + step);
                configDirty = true;
                tell(player, "§b跟随范围 §f" + followRangeLabel(cfg)
                    + " §7(" + String.format("%.1f", cfg.followDistance) + " 格)");
            } else if (slot == SLOT_HEAL) {
                Entity entity = petEntity(player, petUuid);
                if (entity instanceof Cow) {
                    Cow pet = (Cow) entity;
                    pet.setHealth(pet.getMaxHealth());
                    data.currentHealth = pet.getHealth();
                    data.maxHealth = pet.getMaxHealth();
                    tell(player, "§a已治疗宠物 §f" + String.format("%.1f", pet.getMaxHealth()) + " HP");
                } else {
                    tell(player, "§c宠物不在身边，无法治疗");
                }
            } else if (slot == SLOT_RECALL) {
                closeNextTick(player);
                mod.recallPet(player);
            } else if (slot == SLOT_CALL) {
                Entity entity = petEntity(player, petUuid);
                if (entity instanceof Cow) {
                    entity.teleportTo(player.getX(), player.getY(), player.getZ());
                    tell(player, "§a宠物已传送到你脚下");
                } else {
                    tell(player, "§c宠物不在这个世界");
                }
            } else if (slot == SLOT_PREV_PAGE) {
                if (player.isShiftKeyDown()) {
                    skillPage = 0;
                } else {
                    skillPage = clampPage(skillPage - 1);
                }
                tell(player, "§e第 §f" + (skillPage + 1) + "§e/§f" + pageCount() + " §e页");
            } else if (slot == SLOT_NEXT_PAGE) {
                if (player.isShiftKeyDown()) {
                    skillPage = pageCount() - 1;
                } else {
                    skillPage = clampPage(skillPage + 1);
                }
                tell(player, "§e第 §f" + (skillPage + 1) + "§e/§f" + pageCount() + " §e页");
            } else if (slot == SLOT_ALL_ON || slot == SLOT_ALL_OFF) {
                boolean on = slot == SLOT_ALL_ON;
                for (Skills.Skill skill : Skills.all()) {
                    cfg.setSkillEnabled(skill.id, on);
                }
                configDirty = true;
                tell(player, on ? "§a已开启全部技能" : "§c已关闭全部技能");
            } else if (slot == SLOT_CLOSE) {
                closeNextTick(player);
            } else {
                tell(player, "§7这一格只是装饰");
            }
        }

        /**
         * Closes the menu on the next server tick.
         *
         * <p>Closing from inside {@code clicked()} is too early: vanilla has not yet sent the
         * container-ack for this click, so the client is left with a transaction it never hears
         * back about ("Server didn't respond to transaction"), and a real client keeps its ghost
         * cursor item. Letting the tick finish first means the ack (and our final content push)
         * go out, and only then does the close packet follow.</p>
         */
        private void closeNextTick(final ServerPlayer player) {
            SuperCowMod mod = SuperCowMod.getInstance();
            if (mod != null) closeRequestedTick = mod.getServerTicks();
        }

        /** Keep panel feedback in the panel, not in the player's action bar. */
        private void tell(ServerPlayer player, String text) {
            lastFeedback = text;
        }

        private static PetMode nextMode(PetMode mode) {
            PetMode[] values = PetMode.values();
            return values[(mode.ordinal() + 1) % values.length];
        }

        private static SummonMode nextSummonMode(SummonMode mode) {
            SummonMode[] values = SummonMode.values();
            return values[(mode.ordinal() + 1) % values.length];
        }

        // ------------------------------------------------------------ sync

        private void pushContents(ServerPlayer player) {
            NonNullList<ItemStack> items = NonNullList.withSize(SIZE + 36, ItemStack.EMPTY);
            for (int i = 0; i < SIZE; i++) {
                items.set(i, container.getItem(i));
            }
            Inventory inv = player.inventory;
            for (int i = 9; i < 36; i++) {
                items.set(SIZE + (i - 9), inv.getItem(i));
            }
            for (int i = 0; i < 9; i++) {
                items.set(SIZE + 27 + i, inv.getItem(i));
            }
            player.connection.send(new ClientboundContainerSetContentPacket(this.containerId, items));
            // Slot -1 = the cursor stack; a stale ghost item there would otherwise survive until
            // the next click.
            player.connection.send(new ClientboundContainerSetSlotPacket(-1, -1, player.inventory.getCarried()));
        }

        // ------------------------------------------------------------ item helpers

        private static List<String> wrapLore(String text, int maxLen) {
            List<String> out = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                return out;
            }
            int i = 0;
            int n = text.length();
            while (i < n) {
                int end = Math.min(n, i + maxLen);
                out.add(text.substring(i, end));
                i = end;
            }
            return out;
        }

        private static ItemStack filler() {
            return make(Items.GRAY_STAINED_GLASS_PANE, " ", java.util.Collections.emptyList());
        }

        private static ItemStack make(Item item, String name, List<String> lore) {
            ItemStack stack = new ItemStack(item);
            stack.setHoverName(new TextComponent(name));
            if (!lore.isEmpty()) {
                CompoundTag display = stack.getOrCreateTagElement("display");
                ListTag list = new ListTag();
                for (String line : lore) {
                    list.add(StringTag.valueOf(net.minecraft.network.chat.Component.Serializer.toJson(new TextComponent(line))));
                }
                display.put("Lore", list);
            }
            return stack;
        }
    }
}
