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
    private static final int SLOT_HEAL = 36;
    private static final int SLOT_RECALL = 37;
    private static final int SLOT_CALL = 38;
    private static final int SLOT_ALL_ON = 39;
    private static final int SLOT_ALL_OFF = 40;
    private static final int SLOT_CLOSE = 53;
    private static final String[] INFO_KEYS = {
        "宠物信息", "技能开关数量", "宠物状态", "使用说明"
    };

    private PetGui() {
    }

    public static void open(ServerPlayer player, Cow pet, UUID ownerUuid) {
        SimpleContainer container = new SimpleContainer(SIZE);
        player.openMenu(new SimpleMenuProvider(
            (syncId, inv, p) -> new PetMenu(syncId, inv, container, pet.getUUID(), ownerUuid,
                p instanceof ServerPlayer ? (ServerPlayer) p : player),
            new TextComponent("§6§l超级牛 · 面板")));
    }

    public static final class PetMenu extends ChestMenu {
        private boolean configDirty;
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
            return pet instanceof Cow && pet.isAlive() && player.distanceToSqr(pet) <= 24.0D * 24.0D;
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
            if (slotId >= 0 && slotId < SIZE) {
                handleClick(slotId, button, sp);
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
            try {
                renderPanel();
            } finally {
                // nothing to re-arm: the panel content is pushed explicitly by pushContents()
            }
        }

        private void renderPanel() {
            SuperCowMod mod = SuperCowMod.getInstance();
            SuperCowConfig cfg = mod.getConfig();
            PetData data = mod.getPetData(ownerUuid);
            Entity entity = containerLevelEntity();
            Cow pet = entity instanceof Cow ? (Cow) entity : null;

            clear();
            for (int i = 0; i < SIZE; i++) {
                container.setItem(i, filler());
            }

            String levelText = data != null ? ("Lv." + data.level) : "无宠物";
            List<String> info = new ArrayList<>();
            if (data != null) {
                info.add("§7生命：§f" + String.format("%.1f", data.currentHealth) + " / " + String.format("%.1f", data.maxHealth));
                info.add("§7经验：§f" + data.exp + " / " + mod.expNeeded(data.level));
                info.add("§7累计击杀：§f" + data.totalKills);
                info.add("§7状态：§f" + (data.isAlive ? "存活" : "阵亡（等待复活）"));
                info.add("§7战斗模式：§f" + PetMode.fromString(data.combatMode).getDisplayName());
                info.add("§7召唤模式：§f" + SummonMode.fromString(data.summonMode).getDisplayName());
            } else {
                info.add("§7还没有宠物牛");
            }
            List<String> summary = new ArrayList<>();
            summary.add("§7" + String.format("%.1f", data != null ? data.currentHealth : 0.0D) + " / "
                + String.format("%.1f", data != null ? data.maxHealth : 0.0D) + " HP");
            summary.add("§7EXP " + (data != null ? data.exp : 0) + " / " + (data != null ? mod.expNeeded(data.level) : 0));
            summary.add("§7击杀 " + (data != null ? data.totalKills : 0));
            summary.add("§8点击刷新状态");
            container.setItem(0, make(Items.COW_SPAWN_EGG, "§6§l超级牛 §e" + levelText, summary));

            int enabled = 0;
            for (Skills.Skill skill : Skills.all()) {
                if (cfg.isSkillEnabled(skill.id)) {
                    enabled++;
                }
            }
            container.setItem(1, make(Items.EXPERIENCE_BOTTLE, "§a技能开关 §f" + enabled + "/" + Skills.all().size(),
                java.util.Arrays.asList("§7已启用：§f" + enabled + " / " + Skills.all().size(),
                    "§7左键 = 全部开启", "§7右键 = 全部关闭")));
            container.setItem(2, make(Items.GOLDEN_APPLE, "§d宠物状态",
                java.util.Arrays.asList("§7存活：" + (pet != null && pet.isAlive() ? "§a是" : "§c否"),
                    "§7坐标：" + (pet != null ? String.format("§f%.0f, %.0f, %.0f", pet.getX(), pet.getY(), pet.getZ()) : "§7-"))));
            container.setItem(3, make(Items.PAPER, "§e使用说明 §8(只读)",
                java.util.Arrays.asList("§7右键宠物牛打开本面板，潜行右键看文字信息", "§7手里拿干草块右键 = 喂食",
                    "§7第一排：宠物信息 / 技能开关(左键全开 右键全关) / 宠物状态(左键刷新) / 本页",
                    "§7第二排起：每个技能一格，左键开 / 右键关",
                    "§7最下一排：战斗模式 / 召唤模式 / 技能风格 / 治疗 / 召回 / 召唤 / 全开 / 全关 / 关闭")));

            container.setItem(SLOT_MODE, make(Items.SHIELD,
                "§b战斗模式 §f" + (data != null ? PetMode.fromString(data.combatMode).getDisplayName() : "-"),
                java.util.Arrays.asList("§7当前：§f" + (data != null ? PetMode.fromString(data.combatMode).getDisplayName() : "-"),
                    "§7点击切换")));
            container.setItem(SLOT_SUMMON_MODE, make(Items.WHEAT,
                "§b召唤模式 §f" + (data != null ? SummonMode.fromString(data.summonMode).getDisplayName() : "-"),
                java.util.Arrays.asList("§7当前：§f" + (data != null ? SummonMode.fromString(data.summonMode).getDisplayName() : "-"),
                    "§7点击切换")));
            container.setItem(SLOT_QUIRK, make(Items.BREWING_STAND,
                "§d技能风格 §f" + net.dabbit.supercow.combat.Quirks.describe(),
                java.util.Arrays.asList("§7当前：§f" + net.dabbit.supercow.combat.Quirks.describe(),
                    "§7safe=全修复 / mixed=混合 / chaos=全BUG", "§7点击循环切换")));

            List<Skills.Skill> skills = Skills.all();
            for (int i = 0; i < skills.size() && SKILL_START + i < SLOT_HEAL; i++) {
                Skills.Skill skill = skills.get(i);
                boolean on = cfg.isSkillEnabled(skill.id);
                List<String> lore = new ArrayList<>();
                lore.add("§7" + skill.hint);
                if (skill.rollable) {
                    lore.add("§8权重：普通 " + skill.weightNormal + " / 狂暴 " + skill.weightRage);
                } else {
                    lore.add("§8不参与随机抽卡");
                }
                lore.add(on ? "§a状态：开启 §8(点击关闭)" : "§c状态：关闭 §8(点击开启)");
                container.setItem(SKILL_START + i, make(on ? Items.LIME_DYE : Items.GRAY_DYE,
                    (on ? "§a✔ " : "§7✘ ") + skill.display, lore));
            }

            container.setItem(SLOT_HEAL, make(Items.GOLDEN_CARROT, "§a治疗宠物", java.util.Collections.singletonList("§7把生命补满")));
            container.setItem(SLOT_RECALL, make(Items.LEAD, "§e收回宠物", java.util.Collections.singletonList("§7宠物回到待命")));
            container.setItem(SLOT_CALL, make(Items.ENDER_EYE, "§e召唤到身边", java.util.Collections.singletonList("§7把宠物传送到你脚下")));
            container.setItem(SLOT_ALL_ON, make(Items.LIME_DYE, "§a全部开启", java.util.Collections.singletonList("§7所有技能重新启用")));
            container.setItem(SLOT_ALL_OFF, make(Items.GRAY_DYE, "§c全部关闭", java.util.Collections.singletonList("§7只保留近战和自动索敌")));
            container.setItem(SLOT_CLOSE, make(Items.BARRIER, "§c关闭", java.util.Collections.singletonList("§7也可以直接按 ESC")));
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

            if (slot >= SKILL_START && slot < SKILL_START + skills.size()) {
                Skills.Skill skill = skills.get(slot - SKILL_START);
                // Left click toggles, right click forces "off" (== a clear "this one is disabled").
                boolean now = button == 1 ? false : !cfg.isSkillEnabled(skill.id);
                cfg.setSkillEnabled(skill.id, now);
                configDirty = true;
                tell(player, "技能 §f" + skill.display + (now ? " §a开启" : " §c关闭"));
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
            final net.minecraft.server.MinecraftServer server = player.server;
            if (server == null) {
                player.closeContainer();
                return;
            }
            server.execute(new Runnable() {
                @Override
                public void run() {
                    if (player.containerMenu == PetMenu.this) {
                        player.closeContainer();
                    }
                }
            });
        }

        /** Action bar feedback so every click visibly does something. */
        private void tell(ServerPlayer player, String text) {
            player.displayClientMessage(new TextComponent(text), true);
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
                    list.add(StringTag.valueOf(line));
                }
                display.put("Lore", list);
            }
            return stack;
        }
    }
}
