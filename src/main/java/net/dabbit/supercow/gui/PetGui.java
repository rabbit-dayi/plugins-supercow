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
        @Override
        public ItemStack clicked(int slotId, int button, ClickType type, Player player) {
            if (!(player instanceof ServerPlayer)) {
                return ItemStack.EMPTY;
            }
            ServerPlayer sp = (ServerPlayer) player;
            if (sp.containerMenu != this) {
                return sp.inventory.getCarried();
            }
            if (slotId >= 0 && slotId < SIZE) {
                handleClick(slotId, sp);
                if (sp.containerMenu == this) {
                    refresh();
                    pushContents(sp);
                }
            }
            // Clicks in the player inventory half (SIZE..SIZE+35) are ignored: nothing may move.
            // Returning the unchanged carried stack makes vanilla treat the click as "rejected"
            // and it resyncs the whole container + the cursor for us - which is exactly what we
            // want, because the client had predicted the icon moving into its inventory.
            // 1.16.5 marks a menu "unsynched" on that path (AbstractContainerMenu.setSynched(p,
            // false)) and then DROPS every following click, so we re-arm it here; otherwise the
            // panel would only ever accept one single click.
            this.setSynched(sp, true);
            return sp.inventory.getCarried();
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
            container.setItem(0, make(Items.COW_SPAWN_EGG, "§6§l超级牛 §e" + levelText, info));

            int enabled = 0;
            for (Skills.Skill skill : Skills.all()) {
                if (cfg.isSkillEnabled(skill.id)) {
                    enabled++;
                }
            }
            container.setItem(1, make(Items.EXPERIENCE_BOTTLE, "§a技能开关",
                java.util.Arrays.asList("§7已启用：§f" + enabled + " / " + Skills.all().size(), "§7点击下面的技能图标切换")));
            container.setItem(2, make(Items.GOLDEN_APPLE, "§d宠物状态",
                java.util.Arrays.asList("§7存活：" + (pet != null && pet.isAlive() ? "§a是" : "§c否"),
                    "§7坐标：" + (pet != null ? String.format("§f%.0f, %.0f, %.0f", pet.getX(), pet.getY(), pet.getZ()) : "§7-"))));
            container.setItem(3, make(Items.BOOK, "§e使用说明",
                java.util.Arrays.asList("§7右键宠物牛打开本面板", "§7手里拿干草块右键 = 喂食",
                    "§7每格代表一个技能，点击开/关", "§7关闭的技能不会被随机抽到")));

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

        // 1.16.5 only has ServerLevel#getEntity(UUID); Level#getEntity takes a numeric id.
        private static Entity petEntity(Player player, UUID petUuid) {
            if (player == null || !(player.level instanceof ServerLevel)) {
                return null;
            }
            return ((ServerLevel) player.level).getEntity(petUuid);
        }

        private void clear() {
            for (int i = 0; i < SIZE; i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
        }

        // ------------------------------------------------------------ click handling

        private void handleClick(int slot, ServerPlayer player) {
            SuperCowMod mod = SuperCowMod.getInstance();
            SuperCowConfig cfg = mod.getConfig();
            PetData data = mod.getPetData(ownerUuid);
            List<Skills.Skill> skills = Skills.all();

            if (slot >= SKILL_START && slot < SKILL_START + skills.size()) {
                Skills.Skill skill = skills.get(slot - SKILL_START);
                boolean now = !cfg.isSkillEnabled(skill.id);
                cfg.setSkillEnabled(skill.id, now);
                configDirty = true;
                player.sendMessage(new TextComponent("§e[超级牛] 技能 §f" + skill.display + " §e已"
                    + (now ? "§a开启" : "§c关闭")), ownerUuid);
                return;
            }
            if (data == null) {
                return;
            }
            if (slot == SLOT_MODE) {
                PetMode next = nextMode(PetMode.fromString(data.combatMode));
                data.combatMode = next.name();
                mod.getCombatManager().setPetMode(ownerUuid, next);
            } else if (slot == SLOT_SUMMON_MODE) {
                SummonMode next = nextSummonMode(SummonMode.fromString(data.summonMode));
                data.summonMode = next.name();
                mod.getSummonManager().setSummonMode(ownerUuid, next);
            } else if (slot == SLOT_QUIRK) {
                String mode = cfg.quirkMode != null ? cfg.quirkMode.toLowerCase() : "mixed";
                String next = mode.startsWith("s") ? "mixed" : (mode.startsWith("m") ? "chaos" : "safe");
                cfg.legacyQuirksEnabled = true;
                cfg.quirkMode = next;
                configDirty = true;
            } else if (slot == SLOT_HEAL) {
                Entity entity = petEntity(player, petUuid);
                if (entity instanceof Cow) {
                    Cow pet = (Cow) entity;
                    pet.setHealth(pet.getMaxHealth());
                    data.currentHealth = pet.getHealth();
                    data.maxHealth = pet.getMaxHealth();
                    player.sendMessage(new TextComponent("§a[超级牛] 宠物已治疗。"), ownerUuid);
                }
            } else if (slot == SLOT_RECALL) {
                player.closeContainer();
                mod.recallPet(player);
            } else if (slot == SLOT_CALL) {
                Entity entity = petEntity(player, petUuid);
                if (entity instanceof Cow) {
                    entity.teleportTo(player.getX(), player.getY(), player.getZ());
                }
            } else if (slot == SLOT_ALL_ON || slot == SLOT_ALL_OFF) {
                boolean on = slot == SLOT_ALL_ON;
                for (Skills.Skill skill : Skills.all()) {
                    cfg.setSkillEnabled(skill.id, on);
                }
                configDirty = true;
                player.sendMessage(new TextComponent("§e[超级牛] 已" + (on ? "§a开启" : "§c关闭") + " §e全部技能。"), ownerUuid);
            } else if (slot == SLOT_CLOSE) {
                player.closeContainer();
            }
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
