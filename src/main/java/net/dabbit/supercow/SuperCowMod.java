package net.dabbit.supercow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.dabbit.supercow.combat.CombatManager;
import net.dabbit.supercow.combat.CombatManager.PetMode;
import net.dabbit.supercow.combat.MinionManager;
import net.dabbit.supercow.combat.QuirkSkills;
import net.dabbit.supercow.combat.SkillCleanup;
import net.dabbit.supercow.combat.Skills;
import net.dabbit.supercow.gui.PetGui;
import net.dabbit.supercow.combat.SpecialAttackManagers;
import net.dabbit.supercow.combat.SummonManager;
import net.dabbit.supercow.combat.SummonManager.SummonMode;
import net.dabbit.supercow.combat.TargetValidator;
import net.dabbit.supercow.config.SuperCowConfig;
import net.dabbit.supercow.data.PetData;
import net.dabbit.supercow.pathfinding.PathfindingManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * SuperCow - Fabric 1.16.5 port of the Bukkit 1.19.4 plugin.
 *
 * Numeric values are conservative on purpose (see SuperCowConfig).
 */
public class SuperCowMod implements ModInitializer {
    public static final String MOD_ID = "supercow";
    public static final String TAG_PET = "supercow_pet";
    public static final String TAG_OWNER_PREFIX = "supercow_owner:";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, UUID> DAMAGE_OWNER_BY_VICTIM = new HashMap<>();

    private static SuperCowMod instance;
    private static MinecraftServer server;
    private static long serverTicks;

    private final Map<UUID, PetData> petDataByOwner = new HashMap<>();
    private final Map<UUID, Cow> activePetByOwner = new HashMap<>();
    private final Map<UUID, UUID> ownerByPetEntity = new HashMap<>();
    private final Map<UUID, Long> lastFeedTick = new HashMap<>();
    private static final class GuiClickSequence {
        UUID pet;
        long firstTick;
        long lastTick;
        int count;
    }
    private final Map<UUID, GuiClickSequence> guiClicks = new HashMap<>();
    private final Map<UUID, Long> nextLostPetScanTick = new HashMap<>();

    private SuperCowConfig config;
    private PathfindingManager pathfindingManager;
    private SummonManager summonManager;
    private CombatManager combatManager;

    public static SuperCowMod getInstance() {
        return instance;
    }

    public static MinecraftServer getServer() {
        return server;
    }

    public static long getServerTicksStatic() {
        return serverTicks;
    }

    public long getServerTicks() {
        return serverTicks;
    }

    public SuperCowConfig getConfig() {
        return config;
    }

    public PathfindingManager getPathfindingManager() {
        return pathfindingManager;
    }

    public SummonManager getSummonManager() {
        return summonManager;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }

    @Override
    public void onInitialize() {
        instance = this;
        config = SuperCowConfig.load(new File(configDir(), "supercow-config.json"));
        pathfindingManager = new PathfindingManager();
        summonManager = new SummonManager(this, pathfindingManager);
        combatManager = new CombatManager(this, pathfindingManager, summonManager);

        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            loadPets();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            savePets();
            if (combatManager != null) {
                combatManager.cleanupAll();
            }
            server = null;
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                onPlayerJoin(player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                // FIXED LEAK: combat state (targets, glowing, summons, minions, tracked skill
                // entities) used to survive a logout, so a pet with no online owner kept the
                // glowing target and leftover helper entities around until the server restarted.
                UUID uuid = player.getUUID();
                Cow pet = getActivePet(uuid);
                guiClicks.remove(uuid);
                combatManager.onPetRemovedOrDied(uuid, pet);
                SkillCleanup.cleanupForOwner(uuid);
                savePets();
            }
        });

        UseEntityCallback.EVENT.register(this::onUseEntity);
        AttackEntityCallback.EVENT.register(this::onAttackEntity);
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> SuperCowCommand.register(dispatcher));


        System.out.println("[SuperCow] Fabric 1.16.5 port initialized.");
    }

    private File configDir() {
        return FabricLoader.getInstance().getConfigDir().toFile();
    }

    // ---------------------------------------------------------------- pets data

    public PetData getPetData(UUID ownerUuid) {
        return petDataByOwner.get(ownerUuid);
    }

    /** Owner UUIDs that currently have pet data (used by the GUI to resolve a pet robustly). */
    public java.util.Set<UUID> getPetOwnerUuids() {
        return new java.util.HashSet<>(petDataByOwner.keySet());
    }

    public Cow getActivePet(UUID ownerUuid) {
        Cow cow = activePetByOwner.get(ownerUuid);
        if (cow != null && (!cow.isAlive() || cow.removed)) {
            activePetByOwner.remove(ownerUuid);
            return null;
        }
        return cow;
    }

    public UUID getOwnerOfPetEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        return ownerByPetEntity.get(entity.getUUID());
    }

    public int expNeeded(int level) {
        return config.baseExpNeeded + Math.max(0, level - 1) * config.expPerLevelStep;
    }

    /**
     * FIXED BUG (SuperCow.kt:801-808): expNeeded used to be computed once outside the
     * level-up while-loop, so multi-level gains consumed the wrong thresholds.
     */
    public void addExpToPet(UUID ownerUuid, Cow pet, int amount) {
        PetData data = petDataByOwner.get(ownerUuid);
        if (data == null || amount <= 0) {
            return;
        }
        data.exp += amount;
        boolean leveled = false;
        while (data.level < config.maxLevel && data.exp >= expNeeded(data.level)) {
            data.exp -= expNeeded(data.level);
            data.level++;
            leveled = true;
        }
        if (data.level >= config.maxLevel) {
            data.exp = Math.min(data.exp, expNeeded(config.maxLevel));
        }
        if (leveled) {
            applyLevelStats(data, pet);
            if (pet != null && pet.isAlive()) {
                pet.level.playSound(null, pet.getX(), pet.getY(), pet.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.8F, 1.2F);
                if (pet.level instanceof ServerLevel) {
                    ((ServerLevel) pet.level).sendParticles(ParticleTypes.HAPPY_VILLAGER, pet.getX(), pet.getY() + 1.0D, pet.getZ(), 15, 0.6D, 0.6D, 0.6D, 0.05D);
                }
            }
            ServerPlayer owner = getPlayer(ownerUuid);
            if (owner != null) {
                owner.sendMessage(new TextComponent("\u00A7a\u00A7l[\u8D85\u7EA7\u725B] \u4F60\u7684\u725B\u5347\u5230\u4E86 Lv." + data.level + "\uFF01"), ownerUuid);
            }
        }
    }

    private void applyLevelStats(PetData data, Cow pet) {
        double maxHp = config.baseHealth + (data.level - 1) * config.healthPerLevel;
        data.maxHealth = maxHp;
        if (pet != null && pet.isAlive()) {
            AttributeInstance hp = pet.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(maxHp);
            }
            AttributeInstance speed = pet.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                double sp = Math.min(config.maxSpeed, config.baseSpeed + (data.level - 1) * config.speedPerLevel);
                speed.setBaseValue(sp);
            }
            if (pet.getHealth() > maxHp) {
                pet.setHealth((float) maxHp);
            }
        }
    }

    // ---------------------------------------------------------------- taming

    private InteractionResult onUseEntity(Player player, net.minecraft.world.level.Level world, InteractionHand hand, Entity entity, net.minecraft.world.phys.EntityHitResult hit) {
        if (world.isClientSide || !(player instanceof ServerPlayer) || !(entity instanceof Cow)) {
            return InteractionResult.PASS;
        }
        return handleCowRightClick((ServerPlayer) player, (Cow) entity, hand)
            ? InteractionResult.SUCCESS
            : InteractionResult.PASS;
    }

    /**
     * Shared right-click handler used by the Fabric UseEntityCallback and by our own
     * ServerGamePacketListenerImpl mixin. Returns true when the interaction was consumed,
     * i.e. the packet should be cancelled so vanilla cow behaviour does not also run.
     */
    public boolean handleCowRightClick(ServerPlayer sp, Cow cow, InteractionHand hand) {
        if (!cow.isAlive() || cow.level != sp.level || sp.distanceToSqr(cow) > 36.0D) {
            return false;
        }
        // A client may send both hand interactions for one physical click.
        if (hand != InteractionHand.MAIN_HAND) {
            return cow.getTags().contains(TAG_PET);
        }
        ItemStack held = sp.getItemInHand(hand);
        boolean isPet = cow.getTags().contains(TAG_PET);
        UUID ownerUuid = sp.getUUID();

        if (!isPet) {
            if (held.getItem() != Items.HAY_BLOCK) {
                return false;
            }
            PetData existing = petDataByOwner.get(ownerUuid);
            if (existing != null && existing.isAlive && getActivePet(ownerUuid) != null) {
                sp.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u4F60\u5DF2\u7ECF\u6709\u4E00\u53EA\u5BA0\u7269\u725B\u4E86\uFF01"), ownerUuid);
                return true;
            }
            if (Math.random() > config.tameChance) {
                sp.sendMessage(new TextComponent("\u00A7e[\u8D85\u7EA7\u725B] \u8FD9\u53EA\u725B\u8FD8\u4E0D\u613F\u610F\u8DDF\u968F\u4F60\uFF0C\u518D\u8BD5\u8BD5\u5427\uFF01"), ownerUuid);
                return true;
            }
            tame(sp, cow);
            return true;
        }

        // Existing pet interaction
        UUID petOwner = getOwnerOfPetEntity(cow);
        if (petOwner == null) {
            return false;
        }
        if (!petOwner.equals(ownerUuid)) {
            sp.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u8FD9\u662F\u522B\u4EBA\u7684\u5BA0\u7269\u725B\uFF01"), ownerUuid);
            return true;
        }
        if (held.getItem() == Items.HAY_BLOCK) {
            guiClicks.remove(ownerUuid);
            onFeedHay(sp, cow, petOwner);
            return true;
        }
        // Three distinct main-hand clicks within 1.2s; duplicate packets in one tick
        // and the second hand never count. Feeding/sneaking interrupts the sequence.
        if (config.petGuiEnabled && !sp.isShiftKeyDown()
            && (!config.petGuiNeedsEmptyHand || held.isEmpty())) {
            GuiClickSequence clicks = guiClicks.get(ownerUuid);
            if (clicks == null || !cow.getUUID().equals(clicks.pet)
                || serverTicks - clicks.lastTick > 12 || serverTicks - clicks.firstTick > 24) {
                clicks = new GuiClickSequence();
                clicks.pet = cow.getUUID();
                clicks.firstTick = serverTicks;
                clicks.lastTick = serverTicks - 1;
                guiClicks.put(ownerUuid, clicks);
            }
            if (clicks.lastTick != serverTicks) {
                clicks.lastTick = serverTicks;
                if (++clicks.count >= 3) {
                    guiClicks.remove(ownerUuid);
                    PetGui.open(sp, cow, petOwner);
                }
            }
            return true;
        }
        guiClicks.remove(ownerUuid);
        showPetInfo(sp, cow, petOwner);
        return true;
    }

    private InteractionResult onFeedHay(ServerPlayer sp, Cow cow, UUID ownerUuid) {
        PetData data = petDataByOwner.get(ownerUuid);
        if (data == null) {
            return InteractionResult.PASS;
        }
        long now = serverTicks;
        Long last = lastFeedTick.get(ownerUuid);
        if (last != null && (now - last) < config.feedCooldownTicks) {
            long remain = ((config.feedCooldownTicks - (now - last)) / 20L) + 1L;
            sp.sendMessage(new TextComponent("\u00A7e[\u8D85\u7EA7\u725B] \u5582\u98DF\u51B7\u5374\u4E2D\uFF0C\u8FD8\u9700 " + remain + " \u79D2\u3002"), ownerUuid);
            return InteractionResult.CONSUME;
        }
        ItemStack stack = sp.getItemInHand(InteractionHand.MAIN_HAND);
        if (!sp.isCreative()) {
            if (stack.getItem() != Items.HAY_BLOCK) {
                return InteractionResult.PASS;
            }
            stack.shrink(1);
            if (stack.isEmpty()) {
                sp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        lastFeedTick.put(ownerUuid, now);
        float before = cow.getHealth();
        float healed = Math.min((float) config.hayHealAmount, cow.getMaxHealth() - before);
        cow.setHealth(before + healed);
        addExpToPet(ownerUuid, cow, config.hayExpReward);
        if (cow.level instanceof ServerLevel) {
            ((ServerLevel) cow.level).sendParticles(ParticleTypes.HEART, cow.getX(), cow.getY() + 1.0D, cow.getZ(), 5, 0.4D, 0.4D, 0.4D, 0.02D);
        }
        cow.level.playSound(null, cow.getX(), cow.getY(), cow.getZ(), SoundEvents.COW_AMBIENT, SoundSource.NEUTRAL, 0.8F, 1.3F);
        sp.sendMessage(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u5582\u98DF\u6210\u529F\uFF1A+" + String.format("%.1f", healed) + " HP\uFF0C+" + config.hayExpReward + " EXP"), ownerUuid);
        return InteractionResult.SUCCESS;
    }

    private void tame(ServerPlayer player, Cow cow) {
        UUID ownerUuid = player.getUUID();
        PetData data = new PetData(ownerUuid, player.getName().getString(), config.baseHealth);
        petDataByOwner.put(ownerUuid, data);
        turnIntoPet(cow, ownerUuid, data);
        player.level.playSound(null, cow.getX(), cow.getY(), cow.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.7F, 1.4F);
        player.sendMessage(new TextComponent("\u00A7a\u00A7l[\u8D85\u7EA7\u725B] \u606D\u559C\uFF01\u4F60\u9A6F\u670D\u4E86\u4E00\u53EA\u8D85\u7EA7\u725B\uFF01连续右键三次打开面板\uFF0C\u624B\u6301\u5E72\u8349\u5757\u53EF\u5582\u98DF\u3002"), ownerUuid);
        savePets();
    }

    private void turnIntoPet(Cow cow, UUID ownerUuid, PetData data) {
        cow.addTag(TAG_PET);
        cow.addTag(TAG_OWNER_PREFIX + ownerUuid.toString());
        cow.setPersistenceRequired();
        // Legacy movement drives the pet by writing its velocity every tick; vanilla goals would
        // fight that, so the pet runs with no AI while the original "abstract" mover is enabled.
        cow.setNoAi(config.legacyMovementEnabled);
        cow.setCustomName(new TextComponent("\u00A76\u00A7l\u8D85\u7EA7\u725B \u00A7eLv." + data.level));
        cow.setCustomNameVisible(true);
        applyLevelStats(data, cow);
        cow.setHealth((float) Math.min(data.currentHealth <= 0 ? data.maxHealth : data.currentHealth, data.maxHealth));
        activePetByOwner.put(ownerUuid, cow);
        ownerByPetEntity.put(cow.getUUID(), ownerUuid);
        combatManager.setPetMode(ownerUuid, PetMode.fromString(data.combatMode));
        summonManager.setSummonMode(ownerUuid, SummonMode.fromString(data.summonMode));
    }

    /** Update only on text changes, avoiding a metadata packet every tick. */
    private void updatePetSkillName(UUID ownerUuid, Cow pet, PetData data) {
        String skillId = combatManager.getCurrentSkillId(ownerUuid);
        if (pathfindingManager.isSpiraling(pet.getUUID())) {
            skillId = Skills.SKY_SPIRAL;
        }
        String text = "§6§l超级牛 §eLv." + data.level;
        if (skillId != null) {
            Skills.Skill skill = Skills.byId(skillId);
            text += " §7| §b" + (skill == null ? skillId : skill.display);
        }
        if (pet.getCustomName() == null || !text.equals(pet.getCustomName().getString())) {
            pet.setCustomName(new TextComponent(text));
        }
    }

    /** Re-binds an already-loaded pet entity into both ownership maps and refreshes its stats/modes. */
    public void bindPetEntity(UUID ownerUuid, Cow cow) {
        if (ownerUuid == null || cow == null) {
            return;
        }
        PetData data = petDataByOwner.get(ownerUuid);
        if (data != null) {
            turnIntoPet(cow, ownerUuid, data);
        } else {
            activePetByOwner.put(ownerUuid, cow);
            ownerByPetEntity.put(cow.getUUID(), ownerUuid);
        }
    }

    public Cow spawnPetFor(ServerPlayer player, PetData data) {
        if (!(player.level instanceof ServerLevel)) {
            return null;
        }
        ServerLevel level = (ServerLevel) player.level;
        Cow cow = EntityType.COW.create(level);
        if (cow == null) {
            return null;
        }
        // Spawning at owner + 1.5 on a slab/platform can bury the cow half a block inside the
        // terrain; a buried no-AI pet can never move because every travel step is blocked.
        if (!placeAtFreeSpot(level, cow, player.getX() + 1.5D, player.getY(), player.getZ() + 1.5D, player.yRot)) {
            cow.moveTo(player.getX() + 1.5D, player.getY(), player.getZ() + 1.5D, player.yRot, 0.0F);
        }
        double x = cow.getX();
        double z = cow.getZ();
        // The owner's chunk may still be loading when the pet is spawned (join / revive / recall).
        // addFreshEntity() returns false in that case and the cow would stay a detached object that
        // is invisible to mobs and to @e selectors, so force the chunk first and verify the add.
        level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        if (!level.addFreshEntity(cow)) {
            cow.remove();
            return null;
        }
        turnIntoPet(cow, data.ownerUuid, data);
        return cow;
    }

    /**
     * Tries a handful of candidate positions around the owner and keeps the first one where the
     * entity's bounding box does not intersect terrain. Without this a pet spawned onto a slab or
     * into a wall stays buried and its velocity-driven movement never produces any displacement.
     */
    public static boolean placeAtFreeSpot(ServerLevel level, Entity entity, double x, double y, double z, float yRot) {
        double[][] offsets = {
            {1.5D, 0.0D}, {-1.5D, 0.0D}, {0.0D, 1.5D}, {0.0D, -1.5D},
            {2.5D, 0.0D}, {0.0D, -2.5D}, {1.5D, 1.5D}, {-1.5D, -1.5D}, {0.0D, 0.0D}
        };
        double[] lifts = {0.0D, 1.0D, -0.5D, 0.5D};
        // Second pass over the same spots, snapped onto the terrain: a pet is a no-AI mob with a
        // hand-rolled gravity that only accelerates by 0.08/tick, so anything spawned even a few
        // blocks up (a raised platform, the roof of a build, a floating island) fell for ten
        // seconds and died of fall damage before it ever reached the player's feet.
        for (int pass = 0; pass < 2; pass++) {
            for (double[] offset : offsets) {
                for (double lift : lifts) {
                    double baseX = x + offset[0];
                    double baseZ = z + offset[1];
                    double baseY = y + lift;
                    double px = baseX;
                    double py = baseY;
                    double pz = baseZ;
                    if (pass == 1) {
                        py = groundBelow(level, baseX, baseY, baseZ);
                        if (Double.isNaN(py)) {
                            continue;
                        }
                        py += 0.01D;
                    }
                    entity.moveTo(px, py, pz, yRot, 0.0F);
                    if (level.noCollision(entity, entity.getBoundingBox())
                        && (pass == 1 || !isAirPocket(level, entity))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True when the entity has no solid block beneath it (it would just fall). */
    private static boolean isAirPocket(ServerLevel level, Entity entity) {
        net.minecraft.core.BlockPos below = new net.minecraft.core.BlockPos(
            Math.floor(entity.getX()), Math.floor(entity.getY() - 0.6D), Math.floor(entity.getZ()));
        return level.getBlockState(below).getMaterial().isReplaceable();
    }

    /**
     * Highest solid floor at or below {@code fromY} (searching down at most 48 blocks) whose two
     * blocks above are free, returned as the y the entity's feet should sit at.
     *
     * @return the feet y, or NaN when no floor was found
     */
    public static double groundBelow(ServerLevel level, double x, double fromY, double z) {
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        for (int dy = 0; dy <= 48; dy++) {
            int iy = (int) Math.floor(fromY) - dy;
            pos.set(ix, iy, iz);
            boolean solid = !level.getBlockState(pos).getMaterial().isReplaceable()
                && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
            if (!solid) {
                continue;
            }
            pos.set(ix, iy + 1, iz);
            boolean free1 = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
            pos.set(ix, iy + 2, iz);
            boolean free2 = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
            if (free1 && free2) {
                return iy + 1.0D;
            }
        }
        return Double.NaN;
    }

    private void showPetInfo(ServerPlayer sp, Cow cow, UUID ownerUuid) {
        PetData data = petDataByOwner.get(ownerUuid);
        if (data == null) {
            return;
        }
        sp.sendMessage(new TextComponent("\u00A77---------------- \u00A76\u8D85\u7EA7\u725B\u00A77 ----------------"), ownerUuid);
        sp.sendMessage(new TextComponent("\u00A7e \u7B49\u7EA7: \u00A7fLv." + data.level + " \u00A77(EXP " + data.exp + "/" + expNeeded(data.level) + ")"), ownerUuid);
        sp.sendMessage(new TextComponent("\u00A7e \u751F\u547D: \u00A7f" + String.format("%.1f", cow.getHealth()) + "/" + String.format("%.1f", cow.getMaxHealth())), ownerUuid);
        sp.sendMessage(new TextComponent("\u00A7e \u6A21\u5F0F: \u00A7f" + combatManager.getPetMode(ownerUuid).getDisplayName() + " \u00A77| \u5C0F\u725B: " + summonManager.getSummonMode(ownerUuid).getDisplayName()), ownerUuid);
        sp.sendMessage(new TextComponent("\u00A7e \u51FB\u6740: \u00A7f" + data.totalKills + " \u00A77| \u72C2\u66B4: " + (combatManager.isInRageMode(ownerUuid) ? "\u00A7c\u662F" : "\u00A77\u5426")), ownerUuid);
        sp.sendMessage(new TextComponent("\u00A77----------------------------------------"), ownerUuid);
    }

    // ---------------------------------------------------------------- events

    private InteractionResult onAttackEntity(Player player, net.minecraft.world.level.Level world, InteractionHand hand, Entity entity, net.minecraft.world.phys.EntityHitResult hit) {
        if (world.isClientSide || !(player instanceof ServerPlayer) || !(entity instanceof LivingEntity)) {
            return InteractionResult.PASS;
        }
        ServerPlayer sp = (ServerPlayer) player;
        Cow pet = getActivePet(sp.getUUID());
        if (pet == null || !pet.isAlive()) {
            return InteractionResult.PASS;
        }
        if (entity.getUUID().equals(pet.getUUID())) {
            return InteractionResult.PASS;
        }
        combatManager.onOwnerAttackTarget(sp.getUUID(), pet, sp, (LivingEntity) entity);
        return InteractionResult.PASS;
    }

    /**
     * Called from LivingEntityMixin at the HEAD of LivingEntity#hurt.
     * Returning false cancels the damage.
     */
    public boolean onLivingEntityHurt(LivingEntity self, DamageSource source, float amount) {
        if (self == null || source == null || self.level == null || self.level.isClientSide) {
            return true;
        }
        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        if (attacker == null) {
            return true;
        }

        UUID attackerOwner = getOwnerUuidFromTags(attacker);
        if (attackerOwner == null && attacker instanceof Player) {
            attackerOwner = attacker.getUUID();
        }
        if (attackerOwner == null) {
            return true;
        }

        // Decorative actors (including slime/fish) must never deal native contact damage.
        // All scripted hits originate from the actual pet and pass TargetValidator.
        if (attacker.getTags().contains("supercow_quirk_actor")
            || (source.getDirectEntity() != null && source.getDirectEntity().getTags().contains("supercow_quirk_actor"))) {
            return false;
        }

        // Never let a SuperCow pet, summon, projectile or special attack hurt its own owner
        UUID victimOwner = null;
        if (self instanceof Player) {
            victimOwner = self.getUUID();
        } else {
            victimOwner = getOwnerUuidFromTags(self);
        }
        if (victimOwner != null && victimOwner.equals(attackerOwner)) {
            return false;
        }

        // Owner or pet was attacked -> let the pet defend
        UUID selfOwner = getOwnerUuidFromTags(self);
        if (selfOwner != null) {
            Cow pet = getActivePet(selfOwner);
            if (pet != null && self.getUUID().equals(pet.getUUID())) {
                ServerPlayer owner = getPlayer(selfOwner);
                combatManager.onOwnerOrPetAttacked(selfOwner, pet, owner, attacker instanceof LivingEntity ? (LivingEntity) attacker : null);
            } else {
                summonManager.notifyOwnerOrPetAttacked(selfOwner, attacker instanceof LivingEntity ? (LivingEntity) attacker : null);
            }
        } else if (self instanceof ServerPlayer) {
            ServerPlayer owner = (ServerPlayer) self;
            Cow pet = getActivePet(owner.getUUID());
            if (pet != null) {
                combatManager.onOwnerOrPetAttacked(owner.getUUID(), pet, owner, attacker instanceof LivingEntity ? (LivingEntity) attacker : null);
            }
        }
        return true;
    }

    /**
     * Vanilla only treats a hit as "player damage" when DamageSource#getEntity() is a Player,
     * or when a tamed Wolf dealt it (LivingEntity#hurt copies the wolf owner into
     * lastHurtByPlayer). Our pet is a Cow and its damage source keeps the Cow as the attacker
     * on purpose: mob AI reads lastHurtByMob, so retargeting to the owner would stop the cow
     * from tanking. We therefore only copy the owner into lastHurtByPlayer, exactly like the
     * wolf branch. That field is what vanilla reads for both the XP orbs
     * (LivingEntity#dropExperience) and the LAST_DAMAGE_PLAYER loot parameter
     * (LivingEntity#createLootContext), so drops and experience behave like a player kill.
     */
    /** Called from LivingEntityMixin at the HEAD of LivingEntity#die. */
    // Guard against corpses that stop ticking: a mob whose death animation never runs
    // stays in the world forever (no tickDeath -> never removed) and keeps AI/mob-cap slots.
    private final Map<UUID, LivingEntity> dyingCorpses = new HashMap<>();
    private final Map<UUID, Integer> corpseDeathTimeSnapshot = new HashMap<>();
    private final Map<UUID, Integer> corpseStuckTicks = new HashMap<>();

    public void onLivingEntityDie(LivingEntity self, DamageSource source) {
        if (self == null || self.level == null || self.level.isClientSide) {
            return;
        }
        UUID petOwner = getOwnerOfPetEntity(self);
        if (petOwner != null) {
            handlePetDeath(petOwner, self);
            return;
        }

        UUID creditOwner = DAMAGE_OWNER_BY_VICTIM.remove(self.getUUID());
        if (creditOwner == null && source != null) {
            creditOwner = getOwnerUuidFromTags(source.getEntity());
            if (creditOwner == null && source.getEntity() instanceof ServerPlayer) {
                creditOwner = source.getEntity().getUUID();
            }
        }
        if (creditOwner == null) {
            return;
        }
        // Kill credit, done at die() time only so a mob's "recently hit by player" flag is never
        // set during the fight (that would redirect aggro away from the pet). LivingEntity.die()
        // reads these fields BEFORE it drops loot / experience, and our mixin runs at HEAD.
        //   * lastHurtByPlayer -> loot table condition `killed_by_player` + dropExperience()
        //   * awardKillScore   -> statistics (MOB_KILLS), scoreboard kill counters, advancements
        if (self.level instanceof ServerLevel && !(self.getKillCredit() instanceof Player)) {
            ServerPlayer killerPlayer = getPlayer(creditOwner);
            if (killerPlayer != null) {
                self.setLastHurtByPlayer(killerPlayer);
                killerPlayer.awardKillScore(self, 0, source != null ? source : DamageSource.GENERIC);
            }
        }

        PetData data = petDataByOwner.get(creditOwner);
        if (data == null) {
            return;
        }
        data.totalKills++;
        Cow pet = getActivePet(creditOwner);
        addExpToPet(creditOwner, pet, config.expPerKill);
        if (self.level instanceof ServerLevel) {
            dyingCorpses.put(self.getUUID(), self);
            corpseDeathTimeSnapshot.put(self.getUUID(), self.deathTime);
            corpseStuckTicks.put(self.getUUID(), 0);
        }
    }

    /**
     * FIXED BUG (SuperCow.kt:282-283): the old code removed the owner from activePets
     * BEFORE calling combatManager.onPetDeath(ownerName), so combat cleanup silently
     * returned early and left targets glowing and summons alive forever.
     */
    private void handlePetDeath(UUID ownerUuid, LivingEntity petEntity) {
        PetData data = petDataByOwner.get(ownerUuid);
        activePetByOwner.remove(ownerUuid);
        ownerByPetEntity.remove(petEntity.getUUID());
        combatManager.onPetRemovedOrDied(ownerUuid, petEntity instanceof Cow ? (Cow) petEntity : null);
        if (data != null) {
            data.isAlive = false;
            data.deathTimestampMs = System.currentTimeMillis();
        }
        ServerPlayer owner = getPlayer(ownerUuid);
        if (owner != null) {
            owner.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u4F60\u7684\u725B\u9635\u4EA1\u4E86\uFF01\u5C06\u5728 " + config.naturalReviveSeconds + " \u79D2\u540E\u81EA\u52A8\u590D\u6D3B\u3002"), ownerUuid);
        }
        savePets();
    }

    /** FIXED BUG: recallPet never cleaned combat state in the Bukkit version. */
    public void recallPet(ServerPlayer owner) {
        UUID ownerUuid = owner.getUUID();
        PetData data = petDataByOwner.get(ownerUuid);
        Cow pet = getActivePet(ownerUuid);
        if (pet == null && data != null && !data.isAlive && data.deathTimestampMs > 0L) {
            long elapsedSec = Math.max(0L, (System.currentTimeMillis() - data.deathTimestampMs) / 1000L);
            long remainSec = Math.max(1L, config.naturalReviveSeconds - elapsedSec);
            owner.sendMessage(new TextComponent("\u00A7e[\u8D85\u7EA7\u725B] \u4F60\u7684\u725B\u6B63\u5728\u7B49\u5F85\u590D\u6D3B\uFF08\u5269\u4F59 " + remainSec + " \u79D2\uFF09\u3002"), ownerUuid);
            return;
        }
        if (pet != null) {
            combatManager.onPetRemovedOrDied(ownerUuid, pet);
            ownerByPetEntity.remove(pet.getUUID());
            if (pet.level instanceof ServerLevel) {
                ((ServerLevel) pet.level).sendParticles(ParticleTypes.CLOUD, pet.getX(), pet.getY() + 0.6D, pet.getZ(), 10, 0.4D, 0.4D, 0.4D, 0.02D);
            }
            pet.remove();
        }
        activePetByOwner.remove(ownerUuid);
        if (data != null) {
            data.isAlive = false;
            data.deathTimestampMs = 0L;
        }
        owner.sendMessage(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u5DF2\u56DE\u6536\u4F60\u7684\u725B\u3002\u4F7F\u7528 /supercow call \u53EC\u5524\u3002"), ownerUuid);
    }

    public void onPlayerJoin(ServerPlayer player) {
        UUID ownerUuid = player.getUUID();
        PetData data = petDataByOwner.get(ownerUuid);
        if (data == null || !data.isAlive) {
            return;
        }
        if (getActivePet(ownerUuid) == null) {
            spawnPetFor(player, data);
        }
    }

    // ---------------------------------------------------------------- tick

    /**
     * Some entities stop ticking (observed on 1.16.5 with several mods installed): their
     * death animation never advances, so vanilla never removes them and the corpse stays
     * in the world forever. Every corpse our pet killed is watched for 40 ticks; if
     * deathTime never advances we remove it ourselves (loot already dropped inside die()).
     */
    private void tickCorpseCleanup() {
        if (dyingCorpses.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, LivingEntity>> it = dyingCorpses.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, LivingEntity> entry = it.next();
            UUID id = entry.getKey();
            LivingEntity corpse = entry.getValue();
            if (corpse == null || corpse.removed || corpse.getHealth() > 0.0F) {
                it.remove();
                corpseDeathTimeSnapshot.remove(id);
                corpseStuckTicks.remove(id);
                continue;
            }
            int deathTime = corpse.deathTime;
            Integer previous = corpseDeathTimeSnapshot.get(id);
            if (previous == null || previous.intValue() != deathTime) {
                corpseDeathTimeSnapshot.put(id, deathTime);
                corpseStuckTicks.put(id, 0);
                continue;
            }
            int stuck = corpseStuckTicks.getOrDefault(id, 0) + 1;
            corpseStuckTicks.put(id, stuck);
            if (stuck >= 40) {
                if (corpse.deathTime < 20) {
                    corpse.remove();
                }
                it.remove();
                corpseDeathTimeSnapshot.remove(id);
                corpseStuckTicks.remove(id);
            }
        }
    }

    /** The owner's pet cow if it is currently loaded in a level (used to re-bind a lost link). */
    private Cow findLoadedPet(UUID ownerUuid) {
        MinecraftServer srv = server;
        if (srv == null) {
            return null;
        }
        String ownerTag = "supercow_owner:" + ownerUuid;
        for (ServerLevel level : srv.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Cow)) {
                    continue;
                }
                if (!entity.getTags().contains(TAG_PET) || !entity.getTags().contains(ownerTag)) {
                    continue;
                }
                if (!entity.isAlive() || entity.removed) {
                    continue;
                }
                return (Cow) entity;
            }
        }
        return null;
    }

    private static boolean isPetInWorld(Cow pet) {
        if (!(pet.level instanceof ServerLevel)) {
            return false;
        }
        return !pet.removed && ((ServerLevel) pet.level).getEntity(pet.getUUID()) != null;
    }

    private void onEndTick(MinecraftServer srv) {
        server = srv;
        serverTicks++;
        if (petDataByOwner.isEmpty()) {
            // Player without a cow: only the global managers need a tick, skip all the per-pet work.
            tickGlobalManagers();
            return;
        }

        // Iterator instead of a defensive key-set copy: this ran every tick and allocated a new
        // ArrayList (plus boxing of every UUID) for every server, pet or no pet.
        Iterator<Map.Entry<UUID, PetData>> it = petDataByOwner.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PetData> entry = it.next();
            UUID ownerUuid = entry.getKey();
            PetData data = entry.getValue();
            if (data == null) {
                it.remove();
                continue;
            }
            if (!data.isAlive) {
                if (data.deathTimestampMs > 0
                    && (System.currentTimeMillis() - data.deathTimestampMs) >= config.naturalReviveSeconds * 1000L) {
                    ServerPlayer owner = getPlayer(ownerUuid);
                    if (owner != null) {
                        data.isAlive = true;
                        data.deathTimestampMs = 0L;
                        data.currentHealth = data.maxHealth;
                        Cow revived = spawnPetFor(owner, data);
                        if (revived != null) {
                            owner.sendMessage(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u4F60\u7684\u725B\u5DF2\u81EA\u52A8\u590D\u6D3B\uFF01"), ownerUuid);
                        }
                    }
                }
                continue;
            }

            ServerPlayer owner = getPlayer(ownerUuid);
            Cow pet = getActivePet(ownerUuid);
            if ((pet == null || !pet.isAlive()) && data.isAlive && owner != null) {
                // The in-memory link survived a world unload/reload: the cow is still in the map but
                // the entity is gone, so the tick below never sees a pet and never rebuilds it.
                // Only scan when the owner is online and throttle missed global scans to once per second.
                Long nextScan = nextLostPetScanTick.get(ownerUuid);
                if (nextScan == null || serverTicks >= nextScan.longValue()) {
                    Cow rebind = findLoadedPet(ownerUuid);
                    if (rebind != null) {
                        bindPetEntity(ownerUuid, rebind);
                        nextLostPetScanTick.remove(ownerUuid);
                        pet = rebind;
                    } else {
                        nextLostPetScanTick.put(ownerUuid, serverTicks + 20L);
                    }
                }
            }
            if (pet != null && !isPetInWorld(pet)) {
                // A spawn that raced a chunk load used to leave a detached cow in the map: it kept
                // shooting, but mobs could not see or hurt it and @e could not select it.
                // Only the entity link is dropped, the PetData stays so the pet is respawned below.
                combatManager.onPetRemovedOrDied(ownerUuid, pet);
                ownerByPetEntity.remove(pet.getUUID());
                activePetByOwner.remove(ownerUuid);
                pet = null;
            }
            if (pet != null && pet.isAlive()) {
                data.currentHealth = pet.getHealth();
                data.maxHealth = pet.getMaxHealth();
                if (config.regenIntervalTicks > 0 && serverTicks % config.regenIntervalTicks == 0
                    && pet.getHealth() < pet.getMaxHealth()) {
                    pet.setHealth(Math.min(pet.getMaxHealth(), pet.getHealth() + (float) config.regenAmount));
                }
                // A no-AI pet does not fall by itself: when it is airborne with no active route the
                // legacy mover's gravity has to be applied here, otherwise it hovers where it was
                // left. Done in the same pass as the combat tick (it used to walk the map twice and
                // recurse into getActivePet per pet).
                if (!pet.isOnGround()
                    && !pathfindingManager.hasActivePath(pet.getUUID())
                    && !pathfindingManager.isSpiraling(pet.getUUID())) {
                    pathfindingManager.applyIdleGravity(pet);
                }
                if (serverTicks % 2 == 0 && owner != null) {
                    combatManager.tickPetCombat(ownerUuid, pet, owner, data);
                }
                if (serverTicks % 2 == 0) {
                    updatePetSkillName(ownerUuid, pet, data);
                }
                // 牛自己的哞哞声默认关掉：跟着跑的时候一声接一声太吵（配置可开回来）。
                // Only written when the flag actually disagrees: setSilent() marks the entity dirty,
                // and doing that every tick made the client resync the cow constantly.
                boolean wantSilent = !config.petAmbientSoundEnabled;
                if (pet.isSilent() != wantSilent) {
                    pet.setSilent(wantSilent);
                }
            } else if (getActivePet(ownerUuid) == null && data.isAlive && owner != null) {
                // Pet chunk unloaded / entity lost: re-spawn next to the owner.
                spawnPetFor(owner, data);
                nextLostPetScanTick.remove(ownerUuid);
            }
        }

        tickGlobalManagers();
        if (serverTicks % 40 == 0) {
            // Safety net: anything left behind with the supercow_skill tag that the registry no
            // longer knows about is removed, so a skill can never leak an entity permanently.
            for (ServerLevel level : srv.getAllLevels()) {
                SkillCleanup.sweep(level);
            }
        }

        if (serverTicks % 100 == 0) {
            // The pet is a normal world entity, so the world save keeps a copy of it. On the next
            // start a fresh pet is spawned from supercow-pets.json and the saved one becomes a
            // duplicate that hovers where it was left (no-AI mobs never fall). Drop any tagged pet
            // that is not the currently active one.
            for (ServerLevel level : srv.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (!(entity instanceof Cow) || !entity.getTags().contains("supercow_pet")) {
                        continue;
                    }
                    UUID ownerUuid = getOwnerUuidFromTags(entity);
                    if (ownerUuid != null && activePetByOwner.get(ownerUuid) == entity) {
                        continue;
                    }
                    ownerByPetEntity.remove(entity.getUUID());
                    entity.remove();
                }
            }
        }

        if (serverTicks % 1200 == 0) {
            savePets();
        }
    }

    /**
     * The per-tick managers that do not depend on this mod's pet loop. Split out so the empty
     * pet map path can still tick them without running the (much more expensive) per-pet pass.
     */
    private void tickGlobalManagers() {
        tickCorpseCleanup();
        combatManager.tick();
        summonManager.tick();
        MinionManager.tickAll();
        QuirkSkills.tickAll();
        SpecialAttackManagers.tickAll();
        SkillCleanup.tick();
        // Re-arm the passive follow before the mover runs, so a pet whose path was dropped last
        // tick starts walking in this one instead of standing still until the player teleports.
        combatManager.maintainPassiveFollow();
        pathfindingManager.tick();
    }

    // ---------------------------------------------------------------- persistence

    private File petsFile() {
        return new File(configDir(), "supercow-pets.json");
    }

    public void savePets() {
        try {
            JsonObject root = new JsonObject();
            JsonArray pets = new JsonArray();
            for (PetData data : petDataByOwner.values()) {
                if (data == null || data.ownerUuid == null) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("owner", data.ownerUuid.toString());
                o.addProperty("ownerName", data.ownerName == null ? "" : data.ownerName);
                o.addProperty("level", data.level);
                o.addProperty("exp", data.exp);
                o.addProperty("currentHealth", data.currentHealth);
                o.addProperty("maxHealth", data.maxHealth);
                o.addProperty("isAlive", data.isAlive);
                o.addProperty("deathTimestampMs", data.deathTimestampMs);
                o.addProperty("combatMode", data.combatMode);
                o.addProperty("summonMode", data.summonMode);
                o.addProperty("totalKills", data.totalKills);
                pets.add(o);
            }
            root.add("pets", pets);
            File file = petsFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadPets() {
        petDataByOwner.clear();
        activePetByOwner.clear();
        ownerByPetEntity.clear();
        File file = petsFile();
        if (!file.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(file)) {
            JsonElement element = new JsonParser().parse(reader);
            if (!element.isJsonObject()) {
                return;
            }
            JsonArray pets = element.getAsJsonObject().getAsJsonArray("pets");
            if (pets == null) {
                return;
            }
            for (JsonElement el : pets) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                try {
                    UUID owner = UUID.fromString(o.get("owner").getAsString());
                    PetData data = new PetData();
                    data.ownerUuid = owner;
                    data.ownerName = o.has("ownerName") ? o.get("ownerName").getAsString() : "";
                    data.level = o.has("level") ? o.get("level").getAsInt() : 1;
                    data.exp = o.has("exp") ? o.get("exp").getAsInt() : 0;
                    data.currentHealth = o.has("currentHealth") ? o.get("currentHealth").getAsDouble() : config.baseHealth;
                    data.maxHealth = o.has("maxHealth") ? o.get("maxHealth").getAsDouble() : config.baseHealth;
                    data.isAlive = !o.has("isAlive") || o.get("isAlive").getAsBoolean();
                    data.deathTimestampMs = o.has("deathTimestampMs") ? o.get("deathTimestampMs").getAsLong() : 0L;
                    data.combatMode = o.has("combatMode") ? o.get("combatMode").getAsString() : "PROTECTING";
                    data.summonMode = o.has("summonMode") ? o.get("summonMode").getAsString() : "DEFENSIVE";
                    data.totalKills = o.has("totalKills") ? o.get("totalKills").getAsInt() : 0;
                    petDataByOwner.put(owner, data);
                } catch (Exception ignored) {
                    // skip malformed entry
                }
            }
            // Re-spawn pets for players that are already online
            for (UUID ownerUuid : new ArrayList<>(petDataByOwner.keySet())) {
                PetData data = petDataByOwner.get(ownerUuid);
                ServerPlayer owner = getPlayer(ownerUuid);
                if (owner != null && data.isAlive) {
                    spawnPetFor(owner, data);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void reloadConfig() {
        config = SuperCowConfig.load(new File(configDir(), "supercow-config.json"));
    }

    /** Writes the in-memory config back to config/supercow-config.json. */
    public void saveConfigFile() {
        try {
            File file = new File(configDir(), "supercow-config.json");
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            config.save(file);
        } catch (Exception e) {
            System.out.println("[SuperCow] 配置保存失败: " + e);
        }
    }

    // ---------------------------------------------------------------- helpers

    public ServerPlayer getPlayer(UUID uuid) {
        if (server == null || uuid == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(uuid);
    }

    /** Reads the "supercow_owner:<uuid>" tag from any entity / projectile chain. */
    public static UUID getOwnerUuidFromTags(Entity entity) {
        if (entity == null) {
            return null;
        }
        for (String tag : entity.getTags()) {
            if (tag.startsWith(TAG_OWNER_PREFIX)) {
                try {
                    return UUID.fromString(tag.substring(TAG_OWNER_PREFIX.length()));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        Entity owner = null;
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile) {
            owner = ((net.minecraft.world.entity.projectile.Projectile) entity).getOwner();
        }
        if (owner != null && owner != entity) {
            return getOwnerUuidFromTags(owner);
        }
        return null;
    }

    public static void recordDamageOwner(UUID victimUuid, UUID ownerUuid) {
        if (victimUuid != null && ownerUuid != null) {
            DAMAGE_OWNER_BY_VICTIM.put(victimUuid, ownerUuid);
        }
    }

    // ---------------------------------------------------------------- command

    public static final class SuperCowCommand {
        private SuperCowCommand() {
        }

        public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
            dispatcher.register(Commands.literal("supercow")
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("summon").executes(ctx -> summon(ctx.getSource())))
                .then(Commands.literal("call").executes(ctx -> summon(ctx.getSource())))
                .then(Commands.literal("dismiss").executes(ctx -> dismiss(ctx.getSource())))
                .then(Commands.literal("recall").executes(ctx -> dismiss(ctx.getSource())))
                .then(Commands.literal("heal").executes(ctx -> heal(ctx.getSource())))
                .then(Commands.literal("reload").requires(src -> src.hasPermission(2)).executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("mode")
                    .then(Commands.literal("protecting").executes(ctx -> setMode(ctx.getSource(), PetMode.PROTECTING)))
                    .then(Commands.literal("aggressive").executes(ctx -> setMode(ctx.getSource(), PetMode.AGGRESSIVE)))
                    .then(Commands.literal("defensive").executes(ctx -> setMode(ctx.getSource(), PetMode.DEFENSIVE)))
                    .then(Commands.literal("passive").executes(ctx -> setMode(ctx.getSource(), PetMode.PASSIVE))))
                .then(Commands.literal("summonmode")
                    .then(Commands.literal("follow").executes(ctx -> setSummonMode(ctx.getSource(), SummonMode.FOLLOW)))
                    .then(Commands.literal("aggressive").executes(ctx -> setSummonMode(ctx.getSource(), SummonMode.AGGRESSIVE)))
                    .then(Commands.literal("defensive").executes(ctx -> setSummonMode(ctx.getSource(), SummonMode.DEFENSIVE)))
                    .then(Commands.literal("guard").executes(ctx -> setSummonMode(ctx.getSource(), SummonMode.GUARD))))
                .then(Commands.literal("attack")
                    .then(Commands.literal("arrow").executes(ctx -> manualAttack(ctx.getSource(), "arrow")))
                    .then(Commands.literal("fireball").executes(ctx -> manualAttack(ctx.getSource(), "fireball")))
                    .then(Commands.literal("potion").executes(ctx -> manualAttack(ctx.getSource(), "potion")))
                    .then(Commands.literal("firework").executes(ctx -> manualAttack(ctx.getSource(), "firework")))
                    .then(Commands.literal("chicken").executes(ctx -> manualAttack(ctx.getSource(), "chicken")))
                    .then(Commands.literal("music").executes(ctx -> manualAttack(ctx.getSource(), "music")))
                    .then(Commands.literal("exploding_cow").executes(ctx -> manualAttack(ctx.getSource(), "exploding_cow")))
                    .then(Commands.literal("summon").executes(ctx -> manualAttack(ctx.getSource(), "summon"))))
                .then(Commands.literal("quirk").requires(src -> src.hasPermission(2))
                    .executes(ctx -> quirkInfo(ctx.getSource()))
                    .then(Commands.literal("safe").executes(ctx -> setQuirkMode(ctx.getSource(), "safe")))
                    .then(Commands.literal("mixed").executes(ctx -> setQuirkMode(ctx.getSource(), "mixed")))
                    .then(Commands.literal("chaos").executes(ctx -> setQuirkMode(ctx.getSource(), "chaos")))
                    .then(Commands.literal("on").executes(ctx -> setQuirkFlag(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setQuirkFlag(ctx.getSource(), false))))
                .then(Commands.literal("follow")
                    .executes(ctx -> followInfo(ctx.getSource()))
                    .then(Commands.argument("distance", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(2.0D, 24.0D))
                        .executes(ctx -> setFollow(ctx.getSource(),
                            com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "distance")))))
                .then(Commands.literal("skills")
                    .executes(ctx -> skills(ctx.getSource()))
                    .then(Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 16))
                        .executes(ctx -> skillsPage(ctx.getSource(),
                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("gui")
                    .executes(ctx -> openGui(ctx.getSource(), 1))
                    .then(Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 16))
                        .executes(ctx -> openGui(ctx.getSource(),
                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page")))))
                // One literal per registered skill id, generated from the roster so a new skill
                // can never be missing from the command tree (the old hand-written list was).
                .then(skillNode().requires(src -> src.hasPermission(2))));
        }

        /**
         * Builds {@code /supercow skill <id>} from the live roster.
         *
         * <p>Brigadier literals cannot contain spaces, so a skill is addressable by its id, by its
         * display name when that has no space, and by the id without underscores (keeps the old
         * {@code explodingcow} spelling working). Adding a skill to {@link Skills} is therefore
         * enough to make it reachable from the command line.</p>
         */
        private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> skillNode() {
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> node =
                Commands.literal("skill").executes(ctx -> skills(ctx.getSource()));
            attachSkillLiterals(node);
            return node;
        }

        private static void addSkillLiteral(
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root,
            java.util.Set<String> seen, String literal, final String skillId) {
            if (!seen.add(literal)) {
                return;
            }
            root.then(Commands.literal(literal).executes(ctx -> forceSkill(ctx.getSource(), skillId)));
        }

        /**
         * Registers every skill id as a direct child of {@code parent}. Kept separate from
         * {@link #buildSkillArgument()} because Brigadier has no public API to re-parent an
         * already-built literal node.
         */
        private static void attachSkillLiterals(
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> parent) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (Skills.Skill skill : Skills.all()) {
                final String id = skill.id.toLowerCase();
                addSkillLiteral(parent, seen, id, id);
                if (skill.display != null && skill.display.indexOf(' ') < 0 && !skill.display.equals(id)) {
                    addSkillLiteral(parent, seen, skill.display, id);
                }
                if (id.indexOf('_') >= 0) {
                    addSkillLiteral(parent, seen, id.replace("_", ""), id);
                }
            }
        }
        }

        private static ServerPlayer player(CommandSourceStack src) {
            Entity entity = src.getEntity();
            return entity instanceof ServerPlayer ? (ServerPlayer) entity : null;
        }

        private static SuperCowMod mod() {
            return getInstance();
        }

        private static int help(CommandSourceStack src) {
            src.sendSuccess(new TextComponent("\u00A76\u00A7l\u8D85\u7EA7\u725B \u6307\u4EE4\uFF1A"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow summon \u00A77- \u53EC\u5524\u5BA0\u7269\u725B"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow dismiss \u00A77- \u56DE\u6536\u5BA0\u7269\u725B"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow info \u00A77- \u67E5\u770B\u4FE1\u606F"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow mode <protecting|aggressive|defensive|passive>"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow summonmode <follow|aggressive|defensive|guard>"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow attack <arrow|fireball|potion|firework|chicken|music|exploding_cow|summon>"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow skills [\u7B2C\u51E0\u9875] \u00A77- \u5217\u51FA\u5168\u90E8\u6280\u80FD\uFF08\u6BCF\u9875 10 \u6761\uFF09\u4E0E\u6D3B\u52A8\u5B9E\u4F53\u6570"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow follow <2-24> \u00A77- \u8C03\u8DDF\u968F\u8DDD\u79BB\uFF08\u9ED8\u8BA4 20 \u683C\uFF0C\u8D8A\u5927\u8D8A\u8FDC\uFF09"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow gui [\u7B2C\u51E0\u9875] \u00A77- \u6253\u5F00\u5BA0\u7269\u9762\u677F\uFF08\u4E5F\u53EF\u7A7A\u624B\u53F3\u952E\u5BA0\u7269\u725B\uFF1B\u9762\u677F\u5185\u7B2C 41/42 \u683C\u7FFB\u9875\uFF09"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow skill [\u6280\u80FDid] \u00A77- \u5F3A\u5236\u91CA\u653E\uFF08\u4E0D\u586B\u5219\u5217\u51FA\u5168\u90E8 id\uFF0C\u9700\u6743\u96502\uFF09"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow heal \u00A77- \u6CBB\u7597\u5BA0\u7269\u725B"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow quirk <safe|mixed|chaos> \u00A77- \u4FEE\u590D\u7248/\u6DF7\u5408/\u539F\u7248BUG\u7248 (>\u6743\u96502)"), false);
            return 1;
        }

        private static int followInfo(CommandSourceStack src) {
            SuperCowConfig cfg = mod().getConfig();
            // Manual command: String.format is fine here (it never runs on the tick path).
            String line = "\u00A7e\u8DDF\u968F\u8303\u56F4\uFF1A\u00A7f"
                + String.format("%.1f", cfg.followDistance)
                + " \u00A77\u683C\uFF08" + net.dabbit.supercow.gui.PetGui.followRangeLabel(cfg)
                + "\uFF09\u00A78 \u8D70\u51FA " + String.format("%.1f", cfg.followDistance + cfg.followStartBuffer)
                + " \u683C\u624D\u8D77\u8EAB\uFF0C\u505C\u5728 " + String.format("%.1f", Math.max(1.0D, cfg.followDistance - cfg.followStopDistance))
                + " \u683C\uFF1B\u7528 /supercow follow <2-24> \u6216\u9762\u677F\u7B2C 7 \u683C\u8C03\u6574";
            src.sendSuccess(new TextComponent(line), false);
            return 1;
        }

        private static int setFollow(CommandSourceStack src, double distance) {
            SuperCowConfig cfg = mod().getConfig();
            cfg.followDistance = net.dabbit.supercow.gui.PetGui.clampFollow(distance);
            mod().saveConfigFile();
            ServerPlayer p = player(src);
            String msg = "\u00A7b\u8DDF\u968F\u8303\u56F4\u5DF2\u8BBE\u4E3A \u00A7f"
                + String.format("%.1f", cfg.followDistance) + " \u00A77\u683C";
            if (p != null) {
                p.sendMessage(new TextComponent(msg), p.getUUID());
            } else {
                src.sendSuccess(new TextComponent(msg), true);
            }
            return 1;
        }

        private static int openGui(CommandSourceStack src, int page) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            Cow pet = mod().getActivePet(p.getUUID());
            if (pet == null || !pet.isAlive()) {
                p.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u4F60\u6CA1\u6709\u5B58\u6D3B\u7684\u5BA0\u7269\u725B\u3002"), p.getUUID());
                return 0;
            }
            if (!PetGui.setPage(p, page - 1)) PetGui.open(p, pet, p.getUUID(), page - 1);
            return 1;
        }

        private static int skills(CommandSourceStack src) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            UUID owner = p.getUUID();
            SuperCowConfig cfg = mod().getConfig();
            List<Skills.Skill> all = Skills.all();
            int enabledCount = 0;
            for (Skills.Skill sk : all) {
                if (cfg.isSkillEnabled(sk.id)) {
                    enabledCount++;
                }
            }
            p.sendMessage(new TextComponent("\u00A76\u00A7l\u8D85\u7EA7\u725B \u6280\u80FD\u8868 \u00A77\u5171 " + all.size()
                + " \u9879\uFF0C\u5DF2\u5F00\u542F \u00A7f" + enabledCount), owner);
            // The roster is ~60 entries now: printing every line at once scrolls the chat box
            // empty, so the listing is paged 10 lines at a time.
            int perPage = 10;
            int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
            for (int i = 0; i < all.size() && i < perPage; i++) {
                Skills.Skill sk = all.get(i);
                p.sendMessage(new TextComponent((cfg.isSkillEnabled(sk.id) ? "\u00A7a\u2714 " : "\u00A7c\u2718 ")
                    + "\u00A7e" + sk.id + " \u00A7f" + sk.display + " \u00A78"
                    + (sk.rollable ? ("w" + sk.weightNormal + "/" + sk.weightRage) : "\u4E0D\u62BD\u5361")
                    + " \u00A77" + sk.hint), owner);
                if (sk.intro != null && !sk.intro.isEmpty()) {
                    p.sendMessage(new TextComponent("   \u00A76\u25B8 \u00A77" + sk.intro), owner);
                }
            }
            if (pages > 1) {
                int rest = all.size() - perPage;
                p.sendMessage(new TextComponent("\u00A77\u8FD8\u6709 " + rest + " \u9879\uFF1A\u00A7f/supercow skills 2 \u00A77\u2026 " + pages
                    + " \u00A78\uFF08\u9875\uFF09\uFF1B\u9762\u677F\u91CC\u6BCF\u9875 27 \u683C\uFF0C\u7528\u7B2C 41/42 \u683C\u7FFB\u9875\uFF09"), owner);
            }
            p.sendMessage(new TextComponent("\u00A77\u98CE\u683C\uFF1A\u00A7f" + net.dabbit.supercow.combat.Quirks.describe()
                + " \u00A77(safe=\u5168\u4FEE\u590D\u7248 / mixed=\u6DF7\u5408 / chaos=\u5168BUG\u7248)"), owner);
            p.sendMessage(new TextComponent("\u00A77\u6D3B\u52A8\u6280\u80FD\u5B9E\u4F53\uFF1A\u00A7f" + SkillCleanup.countForOwner(owner)
                + " \u00A77(\u9E66\u9E49/\u5154\u5B50 \u00A7f" + MinionManager.countForOwner(owner) + "\u00A77)"), owner);
            return 1;
        }

        /** /supercow skills <page>: the same listing, starting at the given 1-based page. */
        private static int skillsPage(CommandSourceStack src, int page) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            UUID owner = p.getUUID();
            SuperCowConfig cfg = mod().getConfig();
            List<Skills.Skill> all = Skills.all();
            int perPage = 10;
            int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
            int p0 = Math.max(0, Math.min(pages - 1, page - 1));
            p.sendMessage(new TextComponent("\u00A76\u6280\u80FD\u8868 \u7B2C " + (p0 + 1) + "/" + pages + " \u9875"), owner);
            for (int i = p0 * perPage; i < all.size() && i < (p0 + 1) * perPage; i++) {
                Skills.Skill sk = all.get(i);
                p.sendMessage(new TextComponent((cfg.isSkillEnabled(sk.id) ? "\u00A7a\u2714 " : "\u00A7c\u2718 ")
                    + "\u00A7e" + sk.id + " \u00A7f" + sk.display + " \u00A77" + sk.hint), owner);
                if (sk.intro != null && !sk.intro.isEmpty()) {
                    p.sendMessage(new TextComponent("   \u00A76\u25B8 \u00A77" + sk.intro), owner);
                }
            }
            return 1;
        }

        private static int forceSkill(CommandSourceStack src, String skill) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            SuperCowMod m = mod();
            Cow pet = m.getActivePet(p.getUUID());
            if (pet == null || !(p.level instanceof ServerLevel)) {
                p.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u4F60\u6CA1\u6709\u5B58\u6D3B\u7684\u5BA0\u7269\u725B\u3002"), p.getUUID());
                return 0;
            }
            if (!m.getConfig().isSkillEnabled(skill)) {
                p.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u6280\u80FD \u00A7f" + skill
                    + " \u00A7c\u5DF2\u88AB\u5173\u95ED\uFF0C\u53EF\u5728\u9762\u677F\u4E2D\u5F00\u542F\u3002"), p.getUUID());
                return 0;
            }
            boolean ok = m.getCombatManager().forceSkill((ServerLevel) p.level, p.getUUID(), skill);
            if (!ok) {
                p.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u9644\u8FD1\u6CA1\u6709\u53EF\u653B\u51FB\u7684\u76EE\u6807\u3002"), p.getUUID());
                return 0;
            }
            Skills.Skill meta = Skills.byId(skill);
            String title = meta != null ? (meta.display + " \u00A78(" + skill + ")") : skill;
            p.sendMessage(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u91CA\u653E\u6280\u80FD\uFF1A\u00A7e" + title), p.getUUID());
            if (meta != null && meta.intro != null && !meta.intro.isEmpty()) {
                p.sendMessage(new TextComponent("\u00A76\u25B8 \u00A77" + meta.intro), p.getUUID());
            }
            return 1;
        }

        private static int quirkInfo(CommandSourceStack src) {
            SuperCowConfig cfg = mod().getConfig();
            src.sendSuccess(new TextComponent("\u00A76\u00A7l\u6280\u80FD\u98CE\u683C\uFF1A\u00A7f"
                + net.dabbit.supercow.combat.Quirks.describe()), false);
            src.sendSuccess(new TextComponent("\u00A7esafe  \u00A77- \u5168\u90E8\u4F7F\u7528\u4FEE\u590D\u7248\u903B\u8F91\uFF08\u7A33\uFF0C\u8FD9\u6B21\u66F4\u65B0\u524D\u7684\u624B\u611F\uFF09"), false);
            src.sendSuccess(new TextComponent("\u00A7emixed \u00A77- \u6BCF\u6B21\u91CA\u653E\u968F\u673A\uFF0C\u4FEE\u590D\u7248\u4E0EBUG\u7248\u90FD\u4F1A\u51FA\u73B0\uFF08\u9ED8\u8BA4 "
                + Math.round(cfg.quirkChaosChance * 100.0D) + "% BUG\u7248\uFF09"), false);
            src.sendSuccess(new TextComponent("\u00A7echaos \u00A77- \u5168\u90E8\u4F7F\u7528\u539F\u7248BUG\u7248\uFF08\u6709\u7279\u8272\uFF09"), false);
            src.sendSuccess(new TextComponent("\u00A77\u5F00\u5173\uFF1A/supercow quirk <safe|mixed|chaos|on|off>\uFF08\u9700\u6743\u96502\uFF09"), false);
            return 1;
        }

        private static int setQuirkMode(CommandSourceStack src, String mode) {
            SuperCowMod m = mod();
            SuperCowConfig cfg = m.getConfig();
            cfg.legacyQuirksEnabled = true;
            cfg.quirkMode = mode;
            m.saveConfigFile();
            src.sendSuccess(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u6280\u80FD\u98CE\u683C\u5DF2\u5207\u6362\u4E3A \u00A7e" + mode
                + "\u00A77\uFF08" + net.dabbit.supercow.combat.Quirks.describe() + "\uFF09"), true);
            return 1;
        }

        private static int setQuirkFlag(CommandSourceStack src, boolean enabled) {
            SuperCowMod m = mod();
            SuperCowConfig cfg = m.getConfig();
            cfg.legacyQuirksEnabled = enabled;
            m.saveConfigFile();
            src.sendSuccess(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u539F\u7248BUG\u6027\u884C\u4E3A\uFF1A\u00A7e"
                + (enabled ? "\u5F00\u542F" : "\u5173\u95ED") + "\u00A77\uFF08" + net.dabbit.supercow.combat.Quirks.describe() + "\uFF09"), true);
            return 1;
        }

        private static int info(CommandSourceStack src) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            SuperCowMod m = mod();
            Cow pet = m.getActivePet(p.getUUID());
            PetData data = m.getPetData(p.getUUID());
            if (pet == null || data == null) {
                p.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u4F60\u8FD8\u6CA1\u6709\u5BA0\u7269\u725B\u3002\u624B\u6301\u5E72\u8349\u5757\u53F3\u952E\u4E00\u53EA\u725B\u5373\u53EF\u9A6F\u670D\u3002"), p.getUUID());
                return 0;
            }
            m.showPetInfo(p, pet, p.getUUID());
            return 1;
        }

        private static int summon(CommandSourceStack src) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            SuperCowMod m = mod();
            PetData data = m.getPetData(p.getUUID());
            if (data == null) {
                p.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u4F60\u8FD8\u6CA1\u6709\u5BA0\u7269\u725B\u3002"), p.getUUID());
                return 0;
            }
            if (m.getActivePet(p.getUUID()) != null) {
                p.sendMessage(new TextComponent("\u00A7e[\u8D85\u7EA7\u725B] \u5BA0\u7269\u725B\u5DF2\u5728\u8EAB\u8FB9\u3002"), p.getUUID());
                return 0;
            }
            data.isAlive = true;
            data.deathTimestampMs = 0L;
            Cow cow = m.spawnPetFor(p, data);
            if (cow != null) {
                p.sendMessage(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u5BA0\u7269\u725B\u5DF2\u53EC\u5524\u3002"), p.getUUID());
                return 1;
            }
            return 0;
        }

        private static int dismiss(CommandSourceStack src) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            mod().recallPet(p);
            return 1;
        }

        private static int heal(CommandSourceStack src) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            Cow pet = mod().getActivePet(p.getUUID());
            if (pet == null) {
                return 0;
            }
            pet.setHealth(pet.getMaxHealth());
            if (pet.level instanceof ServerLevel) {
                ((ServerLevel) pet.level).sendParticles(ParticleTypes.HEART, pet.getX(), pet.getY() + 1.0D, pet.getZ(), 8, 0.4D, 0.4D, 0.4D, 0.02D);
            }
            p.sendMessage(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u5DF2\u6CBB\u7597\u5BA0\u7269\u725B\u3002"), p.getUUID());
            return 1;
        }

        private static int reload(CommandSourceStack src) {
            mod().reloadConfig();
            src.sendSuccess(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u914D\u7F6E\u5DF2\u91CD\u8F7D\u3002"), true);
            return 1;
        }

        private static int setMode(CommandSourceStack src, PetMode mode) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            SuperCowMod m = mod();
            PetData data = m.getPetData(p.getUUID());
            if (data == null) {
                return 0;
            }
            data.combatMode = mode.name();
            m.getCombatManager().setPetMode(p.getUUID(), mode);
            p.sendMessage(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u6A21\u5F0F\u5DF2\u8BBE\u4E3A\uFF1A" + mode.getDisplayName()), p.getUUID());
            return 1;
        }

        private static int setSummonMode(CommandSourceStack src, SummonMode mode) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            SuperCowMod m = mod();
            PetData data = m.getPetData(p.getUUID());
            if (data == null) {
                return 0;
            }
            data.summonMode = mode.name();
            m.getSummonManager().setSummonMode(p.getUUID(), mode);
            p.sendMessage(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u5C0F\u725B\u6A21\u5F0F\u5DF2\u8BBE\u4E3A\uFF1A" + mode.getDisplayName()), p.getUUID());
            return 1;
        }

        private static int manualAttack(CommandSourceStack src, String skill) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            SuperCowMod m = mod();
            Cow pet = m.getActivePet(p.getUUID());
            if (pet == null || !(pet.level instanceof ServerLevel)) {
                p.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u6CA1\u6709\u53EF\u7528\u7684\u5BA0\u7269\u725B\u3002"), p.getUUID());
                return 0;
            }
            LivingEntity target = m.findNearestTarget(p, pet);
            if (target == null) {
                p.sendMessage(new TextComponent("\u00A7e[\u8D85\u7EA7\u725B] \u9644\u8FD1\u6CA1\u6709\u76EE\u6807\u3002"), p.getUUID());
                return 0;
            }
            boolean ok = m.getCombatManager().executeNamedAttack(
                (ServerLevel) pet.level,
                p.getUUID(),
                pet,
                p,
                target,
                skill,
                false,
                true
            );
            if (!ok) {
                p.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u6280\u80FD\u65E0\u6CD5\u65BD\u653E\uFF08\u53EF\u80FD\u5728\u51B7\u5374\u4E2D\uFF09\u3002"), p.getUUID());
            }
            return ok ? 1 : 0;
        }

    public LivingEntity findNearestTarget(ServerPlayer player, Cow pet) {
        if (!(pet.level instanceof ServerLevel)) {
            return null;
        }
        ServerLevel level = (ServerLevel) pet.level;
        AABB box = pet.getBoundingBox().inflate(config.maxTargetDistance);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!TargetValidator.isValidCombatTarget(candidate, player.getUUID(), pet, false)) {
                continue;
            }
            double d = pet.distanceToSqr(candidate);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        return best;
    }
}
