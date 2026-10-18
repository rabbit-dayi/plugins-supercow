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
            onFeedHay(sp, cow, petOwner);
            return true;
        }
        // Empty-hand right-click opens the chest GUI; sneaking keeps the plain text info dump.
        if (config.petGuiEnabled && !sp.isShiftKeyDown()
            && (!config.petGuiNeedsEmptyHand || held.isEmpty())) {
            PetGui.open(sp, cow, petOwner);
            return true;
        }
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
        player.sendMessage(new TextComponent("\u00A7a\u00A7l[\u8D85\u7EA7\u725B] \u606D\u559C\uFF01\u4F60\u9A6F\u670D\u4E86\u4E00\u53EA\u8D85\u7EA7\u725B\uFF01\u53F3\u952E\u67E5\u770B\u4FE1\u606F\uFF0C\u624B\u6301\u5E72\u8349\u5757\u53EF\u5582\u98DF\u3002"), ownerUuid);
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
        for (double[] offset : offsets) {
            for (double lift : lifts) {
                double px = x + offset[0];
                double py = y + lift;
                double pz = z + offset[1];
                entity.moveTo(px, py, pz, yRot, 0.0F);
                if (level.noCollision(entity, entity.getBoundingBox())) {
                    return true;
                }
            }
        }
        return false;
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
        Cow pet = getActivePet(ownerUuid);
        if (pet != null) {
            combatManager.onPetRemovedOrDied(ownerUuid, pet);
            ownerByPetEntity.remove(pet.getUUID());
            if (pet.level instanceof ServerLevel) {
                ((ServerLevel) pet.level).sendParticles(ParticleTypes.CLOUD, pet.getX(), pet.getY() + 0.6D, pet.getZ(), 10, 0.4D, 0.4D, 0.4D, 0.02D);
            }
            pet.remove();
        }
        activePetByOwner.remove(ownerUuid);
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
        for (UUID id : new ArrayList<>(dyingCorpses.keySet())) {
            LivingEntity corpse = dyingCorpses.get(id);
            if (corpse == null || corpse.removed || corpse.getHealth() > 0.0F) {
                dyingCorpses.remove(id);
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
                dyingCorpses.remove(id);
                corpseDeathTimeSnapshot.remove(id);
                corpseStuckTicks.remove(id);
            }
        }
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

        for (UUID ownerUuid : new ArrayList<>(petDataByOwner.keySet())) {
            PetData data = petDataByOwner.get(ownerUuid);
            if (data == null) {
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

            Cow pet = getActivePet(ownerUuid);
            ServerPlayer owner = getPlayer(ownerUuid);
            if (pet != null && !isPetInWorld(pet)) {
                // A spawn that raced a chunk load used to leave a detached cow in the map: it kept
                // shooting, but mobs could not see or hurt it and @e could not select it.
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
                if (serverTicks % 2 == 0) {
                    combatManager.tickPetCombat(ownerUuid, pet, owner, data);
                }
            } else if (owner != null && getActivePet(ownerUuid) == null && petDataByOwner.get(ownerUuid) != null
                && petDataByOwner.get(ownerUuid).isAlive) {
                // Pet chunk unloaded / entity lost: re-spawn next to the owner.
                spawnPetFor(owner, petDataByOwner.get(ownerUuid));
            }
        }

        tickCorpseCleanup();
        combatManager.tick();
        summonManager.tick();
        MinionManager.tickAll();
        QuirkSkills.tickAll();
        SpecialAttackManagers.tickAll();
        SkillCleanup.tick();
        pathfindingManager.tick();
        // A no-AI pet does not fall by itself: when it is airborne with no active route the
        // legacy mover's gravity has to be applied here, otherwise it hovers where it was left.
        for (UUID ownerUuid : new ArrayList<>(activePetByOwner.keySet())) {
            Cow flying = getActivePet(ownerUuid);
            if (flying != null && flying.isAlive() && !flying.isOnGround()
                && !pathfindingManager.hasActivePath(flying.getUUID())
                && !pathfindingManager.isSpiraling(flying.getUUID())) {
                pathfindingManager.applyIdleGravity(flying);
            }
        }
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
                .then(Commands.literal("skills").executes(ctx -> skills(ctx.getSource())))
                .then(Commands.literal("gui").executes(ctx -> openGui(ctx.getSource())))
                .then(Commands.literal("skill").requires(src -> src.hasPermission(2))
                    .then(Commands.literal("arrow").executes(ctx -> forceSkill(ctx.getSource(), "arrow")))
                    .then(Commands.literal("fireball").executes(ctx -> forceSkill(ctx.getSource(), "fireball")))
                    .then(Commands.literal("potion").executes(ctx -> forceSkill(ctx.getSource(), "potion")))
                    .then(Commands.literal("firework").executes(ctx -> forceSkill(ctx.getSource(), "firework")))
                    .then(Commands.literal("chicken").executes(ctx -> forceSkill(ctx.getSource(), "chicken")))
                    .then(Commands.literal("music").executes(ctx -> forceSkill(ctx.getSource(), "music")))
                    .then(Commands.literal("exploding_cow").executes(ctx -> forceSkill(ctx.getSource(), "exploding_cow")))
                    .then(Commands.literal("summon").executes(ctx -> forceSkill(ctx.getSource(), "summon")))
                    .then(Commands.literal("summon_parrots").executes(ctx -> forceSkill(ctx.getSource(), "summon_parrots")))
                    .then(Commands.literal("summon_rabbits").executes(ctx -> forceSkill(ctx.getSource(), "summon_rabbits")))
                    .then(Commands.literal("sky_spiral").executes(ctx -> forceSkill(ctx.getSource(), "sky_spiral")))
                    .then(Commands.literal("cow_rain").executes(ctx -> forceSkill(ctx.getSource(), "cow_rain")))
                    .then(Commands.literal("milk_bomb").executes(ctx -> forceSkill(ctx.getSource(), "milk_bomb")))
                    .then(Commands.literal("moon_walk").executes(ctx -> forceSkill(ctx.getSource(), "moon_walk")))
                    .then(Commands.literal("moo_wave").executes(ctx -> forceSkill(ctx.getSource(), "moo_wave")))
                    .then(Commands.literal("hay_tornado").executes(ctx -> forceSkill(ctx.getSource(), "hay_tornado")))
                    .then(Commands.literal("butterfly_swarm").executes(ctx -> forceSkill(ctx.getSource(), "butterfly_swarm")))
                    .then(Commands.literal("zero_gravity").executes(ctx -> forceSkill(ctx.getSource(), "zero_gravity")))
                    .then(Commands.literal("mirror_cow").executes(ctx -> forceSkill(ctx.getSource(), "mirror_cow")))
                    .then(Commands.literal("teleport_swap").executes(ctx -> forceSkill(ctx.getSource(), "teleport_swap")))
                    .then(Commands.literal("confetti").executes(ctx -> forceSkill(ctx.getSource(), "confetti")))));
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
            src.sendSuccess(new TextComponent("\u00A7e/supercow skills \u00A77- \u5217\u51FA\u6280\u80FD\u8868\u4E0E\u6D3B\u52A8\u6280\u80FD\u5B9E\u4F53\u6570"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow gui \u00A77- \u6253\u5F00\u5BA0\u7269\u9762\u677F\uFF08\u4E5F\u53EF\u7A7A\u624B\u53F3\u952E\u5BA0\u7269\u725B\uFF09"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow skill <name> \u00A77- \u5F3A\u5236\u91CA\u653E\u4E00\u4E2A\u6280\u80FD (\u9700\u6743\u96502)"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow heal \u00A77- \u6CBB\u7597\u5BA0\u7269\u725B"), false);
            src.sendSuccess(new TextComponent("\u00A7e/supercow quirk <safe|mixed|chaos> \u00A77- \u4FEE\u590D\u7248/\u6DF7\u5408/\u539F\u7248BUG\u7248 (>\u6743\u96502)"), false);
            return 1;
        }

        private static int openGui(CommandSourceStack src) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            Cow pet = mod().getActivePet(p.getUUID());
            if (pet == null || !pet.isAlive()) {
                p.sendMessage(new TextComponent("\u00A7c[\u8D85\u7EA7\u725B] \u4F60\u6CA1\u6709\u5B58\u6D3B\u7684\u5BA0\u7269\u725B\u3002"), p.getUUID());
                return 0;
            }
            PetGui.open(p, pet, p.getUUID());
            return 1;
        }

        private static int skills(CommandSourceStack src) {
            ServerPlayer p = player(src);
            if (p == null) {
                return 0;
            }
            UUID owner = p.getUUID();
            p.sendMessage(new TextComponent("\u00A76\u00A7l\u8D85\u7EA7\u725B \u6280\u80FD\u8868 \u00A77(\u4E0E\u539F\u63D2\u4EF6\u4E00\u81F4)"), owner);
            p.sendMessage(new TextComponent("\u00A7earrow \u00A77\u7BAD\u77E2 / \u00A7efireball \u00A77\u706B\u7403 / \u00A7epotion \u00A77\u836F\u6C34 / \u00A7efirework \u00A77\u70DF\u82B1"), owner);
            p.sendMessage(new TextComponent("\u00A7echicken \u00A77\u8F70\u70B8\u9E21 / \u00A7emusic \u00A77\u97F3\u4E50 / \u00A7eexploding_cow \u00A77\u81EA\u7206\u725B"), owner);
            p.sendMessage(new TextComponent("\u00A7esummon \u00A77\u62A4\u536B\u5C0F\u725B / \u00A7esummon_parrots \u00A77\u9E66\u9E49 / \u00A7esummon_rabbits \u00A77\u5154\u5B50"), owner);
            p.sendMessage(new TextComponent("\u00A77\u8F6C\u5708\u98DE\u5929\uFF1A\u00A7f\u8FFD\u51FB\u65F6 \u00A7e"
                + String.format("%.0f%%", mod().getConfig().skySpiralChance * 100.0D)
                + " \u00A77\u89E6\u53D1 \u00A78(\u5F3A\u5236\uFF1A/supercow skill sky_spiral)"), owner);
            int enabledCount = 0;
            for (Skills.Skill sk : Skills.all()) {
                if (mod().getConfig().isSkillEnabled(sk.id)) {
                    enabledCount++;
                }
            }
            p.sendMessage(new TextComponent("\u00A77\u62BD\u89C9\u6280\u80FD\uFF1A\u00A7f" + enabledCount + "/" + Skills.all().size()
                + " \u00A77\u5DF2\u5F00\u542F \u00A78(\u53F3\u952E\u5BA0\u7269\u725B\u6253\u5F00\u9762\u677F\u5F00\u5173)"), owner);
            p.sendMessage(new TextComponent("\u00A77\u65B0\u6280\u80FD\uFF1A\u00A7fcow_rain milk_bomb moon_walk moo_wave hay_tornado"), owner);
            p.sendMessage(new TextComponent("\u00A77          \u00A7fbutterfly_swarm zero_gravity mirror_cow teleport_swap confetti"), owner);
            p.sendMessage(new TextComponent("\u00A77\u98CE\u683C\uFF1A\u00A7f" + net.dabbit.supercow.combat.Quirks.describe()
                + " \u00A77(safe=\u5168\u4FEE\u590D\u7248 / mixed=\u6DF7\u5408 / chaos=\u5168BUG\u7248)"), owner);
            p.sendMessage(new TextComponent("\u00A77\u6D3B\u52A8\u6280\u80FD\u5B9E\u4F53\uFF1A\u00A7f" + SkillCleanup.countForOwner(owner)
                + " \u00A77(\u9E66\u9E49/\u5154\u5B50 \u00A7f" + MinionManager.countForOwner(owner) + "\u00A77)"), owner);
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
            p.sendMessage(new TextComponent("\u00A7a[\u8D85\u7EA7\u725B] \u91CA\u653E\u6280\u80FD\uFF1A\u00A7e" + skill), p.getUUID());
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
