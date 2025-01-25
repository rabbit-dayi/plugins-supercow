// SuperCow.kt
package net.dabbit.supercow

import net.dabbit.supercow.combat.ArrowEffectManager
import net.dabbit.supercow.combat.ArrowTrajectoryCalculator
import net.dabbit.supercow.combat.CombatManager
import net.dabbit.supercow.combat.TargetValidator
import org.bukkit.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.command.CommandExecutor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.collections.HashMap
import org.bukkit.metadata.FixedMetadataValue

import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

import net.dabbit.supercow.pathfinding.PathfindingManager
import net.dabbit.supercow.combat.*
import org.bukkit.entity.*
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerJoinEvent

class SuperCow : JavaPlugin(), Listener {
    private val petData = HashMap<String, SuperCowData>()
    private val activePets = HashMap<String, Cow>()
    private lateinit var dataFile: File
    private lateinit var config: YamlConfiguration
    lateinit var combatManager: CombatManager
    private lateinit var pathfindingManager: PathfindingManager
    private lateinit var commandExecutor: SuperCowCommand  // 添加这行
    private lateinit var fireworkManager: FireworkManager // 烟花
    private lateinit var projectilmgr: ProjectileManager // 烟花
    lateinit var summonmgr: SummonManager  private set// 烟花




    private val respawnTasks = HashMap<String, BukkitRunnable>()
    private val respawnBossBars = HashMap<String, BossBar>()


    private lateinit var arrowEffectManager: ArrowEffectManager
    private lateinit var targetValidator: TargetValidator
    private lateinit var trajectoryCalculator: ArrowTrajectoryCalculator

    private val followMode = HashMap<String, Boolean>() // 存储跟随模式状态
    private val lostPets = HashMap<String, Cow>() // 存储丢失的宠物

    private val spawnFailCounts = HashMap<String, Int>()//生成失败计数器


    companion object {
        const val PREFIX = "§6[SuperCow] §r"
        const val MAX_FOLLOW_DISTANCE = 30.0 // 最大跟随距离
        const val TELEPORT_DISTANCE = 20.0 // 触发传送的距离
        const val RESPAWN_DELAY = 100L  // 5秒 = 100 ticks
        const val RESPAWN_MESSAGE_DELAY = 20L  // 1秒 = 20 ticks
        const val MAX_SPAWN_FAILS = 3 // 最大生成失败次数
    }

    fun fixedMetadataValue(): FixedMetadataValue {
        return FixedMetadataValue(this, true)
    }

    @EventHandler
    fun onPlayerRightClick(event: PlayerInteractEvent) {
        val player = event.player

        // 只处理主手操作
        if (event.hand != EquipmentSlot.HAND) return

        // 检查玩家手中的物品是否是牛奶桶
        if (player.inventory.itemInMainHand.type == Material.MILK_BUCKET) {
            // 取消事件以防止默认行为（如饮用牛奶）
//            event.isCancelled = true

            // 检查是否已经捕获过超级小母牛
            if (!petData.containsKey(player.name)) {
                player.sendMessage("${PREFIX}§c你还没有超级小母牛！")
                return
            }

            // 检查玩家是否已经召唤了超级小母牛
            val activeCow = activePets[player.name]
            if (activeCow != null) {
                // 检查牛是否有效（未被杀死）
                if (!activeCow.isValid || activeCow.isDead) {
                    activePets.remove(player.name)
                    player.sendMessage("${PREFIX}§c你的超级小母牛似乎已经不在了！请重新召唤。")
                    return
                }

                // 将小母牛传送到玩家附近
                val safeLocation = findSafeLocation(player.location) ?: player.location
                activeCow.teleport(safeLocation)
                player.sendMessage("${PREFIX}§a你的超级小母牛已经传送到你身边！")

                return
            }

            // 如果没有召唤超级小母牛，则召唤
            if (summonPet(player)) {
                // 如果成功召唤，替换牛奶桶为空桶
                player.inventory.itemInMainHand.type = Material.BUCKET
            }
        }
    }

    override fun onEnable() {
        // 1. 首先创建配置文件
        saveDefaultConfig()

        // 2. 初始化数据文件
        dataFile = File(dataFolder, "pets.yml")
        if (!dataFile.exists()) {
            dataFile.parentFile.mkdirs()
            saveResource("pets.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(dataFile)

        // 3. 按顺序初始化所有管理器
        arrowEffectManager = ArrowEffectManager(this)
        targetValidator = TargetValidator()
        trajectoryCalculator = ArrowTrajectoryCalculator()
        pathfindingManager = PathfindingManager(this)
        fireworkManager =  FireworkManager(this)
        projectilmgr = ProjectileManager(this)
        summonmgr =  SummonManager(this,trajectoryCalculator)


        // 4. 初始化战斗管理器 (确保在其他管理器之后)
        combatManager = CombatManager(
            this,
            pathfindingManager,
            arrowEffectManager,
            targetValidator,
            trajectoryCalculator,
            fireworkManager,
            projectilmgr,
            summonmgr,
        )

        // 5. 注册事件监听器
        server.pluginManager.registerEvents(this, this)
        server.pluginManager.registerEvents(combatManager, this)

        // 6. 初始化命令执行器
        commandExecutor = SuperCowCommand(this)
        getCommand("supercow")?.let { command ->
            command.setExecutor(commandExecutor)
            command.setTabCompleter(commandExecutor)
        }

        // 7. 加载数据
        loadData()

        // 8. 启动所有任务
        startTasks()
        startDistanceCheckTask()
        startFollowTask()
        startLostPetCleanupTask()
        startCombatCheckTask()

        // 9. 显示启动信息
        val rainbowText = """
        ${rainbowify("================SuperCow================")}
        ${rainbowify(" SuperCow plugin has been enabled!")}
        ${rainbowify("           超级小母牛来啦")}
        ${rainbowify("========================================")}
    """.trimIndent()

        rainbowText.lines().forEach { line -> logger.info(line) }
    }
    private fun rainbowify(text: String): String {
        val colors = listOf(
            "\u001B[31m", // 红色
            "\u001B[33m", // 黄色
            "\u001B[32m", // 绿色
            "\u001B[36m", // 青色
            "\u001B[34m", // 蓝色
            "\u001B[35m"  // 紫色
        )
        val reset = "\u001B[0m" // 重置颜色

        // 为每个字符分配一个颜色
        val rainbowText = StringBuilder()
        var colorIndex = 0
        for (char in text) {
            if (char.isWhitespace()) {
                rainbowText.append(char) // 保持空格不变
            } else {
                rainbowText.append(colors[colorIndex % colors.size]).append(char)
                colorIndex++
            }
        }
        rainbowText.append(reset) // 重置颜色
        return rainbowText.toString()
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        // Check if player has a pet
        if (petData.containsKey(player.name)) {
            // Delay the summon by 2 seconds to ensure the player has fully loaded
            object : BukkitRunnable() {
                override fun run() {
                    // Only summon if the player is still online and doesn't already have an active pet
                    if (player.isOnline && !activePets.containsKey(player.name)) {
                        summonPet(player)
                        player.sendMessage("${PREFIX}§a你的超级小母牛已自动召唤！")
                    }
                }
            }.runTaskLater(this, 80L) // 40 ticks = 2 seconds
        }
    }

    @EventHandler
    fun onPetDeath(event: EntityDeathEvent) {
        val deadEntity = event.entity
        if (deadEntity !is Cow) return

        val ownerEntry = activePets.entries.find { it.value == deadEntity } ?: return
        val ownerName = ownerEntry.key
        val owner = server.getPlayer(ownerName) ?: return

        // 清理掉死亡的实体
        activePets.remove(ownerName)
        combatManager.onPetDeath(ownerName)

        // 获取死亡原因
        val deathMessage = getDeathMessage(deadEntity)

        // 发送死亡消息
        owner.sendMessage("${PREFIX}§c你的超级小母牛${deathMessage}！将在5秒后复活...")

        // 取消之前的复活任务（如果存在）
        respawnTasks[ownerName]?.cancel()
        respawnBossBars[ownerName]?.removeAll()

        // 创建 BossBar
        val bossBar = Bukkit.createBossBar(
            "§c超级小母牛复活倒计时: 5 秒",
            BarColor.RED,
            BarStyle.SOLID
        )
        bossBar.addPlayer(owner)
        respawnBossBars[ownerName] = bossBar

        // 创建复活倒计时任务
        val countdownTask = object : BukkitRunnable() {
            var countdown = 5

            override fun run() {
                if (countdown > 0) {
                    // 更新 BossBar
                    bossBar.setTitle("§c超级小母牛复活倒计时: $countdown 秒")
                    bossBar.progress = countdown / 5.0
                    countdown--
                } else {
                    cancel()
                    // 移除 BossBar
                    bossBar.removeAll()
                    respawnBossBars.remove(ownerName)
                    // 复活宠物
                    respawnPet(owner)
                    respawnTasks.remove(ownerName)
                }
            }
        }

        // 启动复活任务
        respawnTasks[ownerName] = countdownTask
        countdownTask.runTaskTimer(this, 0L, RESPAWN_MESSAGE_DELAY)
    }

    private fun getDeathMessage(deadEntity: LivingEntity): String {
        val lastDamageCause = deadEntity.lastDamageCause ?: return "死亡"

        return when (lastDamageCause.cause) {
            EntityDamageEvent.DamageCause.ENTITY_ATTACK -> {
                val damager = (lastDamageCause as EntityDamageByEntityEvent).damager
                when (damager) {
                    is Player -> "被玩家 ${damager.name} 杀死"
                    is Zombie -> "被僵尸咬死"
                    is Skeleton -> "被骷髅射杀"
                    is Creeper -> "被苦力怕炸死"
                    is Spider -> "被蜘蛛咬死"
                    is Enderman -> "被末影人杀死"
                    is Witch -> "被女巫毒死"
                    else -> "被${damager.type.name.lowercase().replace('_', ' ')}杀死"
                }
            }
            EntityDamageEvent.DamageCause.PROJECTILE -> {
                val projectile = (lastDamageCause as EntityDamageByEntityEvent).damager
                when {
                    projectile is Arrow && projectile.shooter is Player ->
                        "被玩家 ${(projectile.shooter as Player).name} 射杀"
                    projectile is Arrow && projectile.shooter is Skeleton ->
                        "被骷髅射手射杀"
                    else -> "被远程武器射杀"
                }
            }
            EntityDamageEvent.DamageCause.FALL -> "摔死了"
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK -> "被烧死了"
            EntityDamageEvent.DamageCause.LAVA -> "在岩浆中游泳"
            EntityDamageEvent.DamageCause.DROWNING -> "淹死了"
            EntityDamageEvent.DamageCause.BLOCK_EXPLOSION -> "被爆炸炸死"
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION -> {
                if (lastDamageCause is EntityDamageByEntityEvent &&
                    lastDamageCause.damager is Creeper) {
                    "被苦力怕炸死"
                } else {
                    "被爆炸炸死"
                }
            }
            EntityDamageEvent.DamageCause.VOID -> "掉入虚空"
            EntityDamageEvent.DamageCause.POISON -> "中毒死亡"
            EntityDamageEvent.DamageCause.MAGIC -> "被魔法杀死"
            EntityDamageEvent.DamageCause.WITHER -> "凋零而死"
            EntityDamageEvent.DamageCause.FALLING_BLOCK -> "被方块砸死"
            EntityDamageEvent.DamageCause.THORNS -> "被荆棘反伤致死"
            EntityDamageEvent.DamageCause.DRAGON_BREATH -> "被龙息杀死"
            EntityDamageEvent.DamageCause.CUSTOM -> "以不明原因死亡"
            EntityDamageEvent.DamageCause.FLY_INTO_WALL -> "撞墙而死"
            EntityDamageEvent.DamageCause.HOT_FLOOR -> "被岩浆块烫死"
            EntityDamageEvent.DamageCause.CRAMMING -> "被挤压致死"
            EntityDamageEvent.DamageCause.DRYOUT -> "缺水而死"
            EntityDamageEvent.DamageCause.FREEZE -> "被冻死"
            EntityDamageEvent.DamageCause.LIGHTNING -> "被闪电劈死"
            else -> "死亡"
        }
    }

    private fun respawnPet(player: Player) {
        // 检查玩家是否在线
        if (!player.isOnline) return

        // 获取宠物数据
        val data = petData[player.name] ?: return

        // 找到安全的复活位置
        val spawnLocation = findSafeLocation(player.location) ?: player.location

        // 生成新的超级小母牛
        val newCow = player.world.spawnEntity(spawnLocation, EntityType.COW) as Cow
        updateCowStats(newCow, player.name, data)

        // 设置初始生命值为最大生命值的一半
        val maxHealth = newCow.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 250.0
        newCow.health = maxHealth / 2

        // 更新活跃宠物列表
        activePets[player.name] = newCow

        // 播放复活效果
        playRespawnEffects(spawnLocation)

        // 发送复活消息
        player.sendMessage("${PREFIX}§a你的超级小母牛cjc已经复活了！")
    }

    private fun playRespawnEffects(location: Location) {
        location.world?.let { world ->
            world.spawnParticle(
                Particle.HEART,
                location.add(0.0, 1.0, 0.0),
                5,
                0.5, 0.5, 0.5,
                0.1
            )
            world.playSound(
                location,
                Sound.ENTITY_PLAYER_LEVELUP,
                1.0f,
                1.0f
            )
        }
    }
    // 添加跟随模式切换方法
    fun toggleFollowMode(player: Player): Boolean {
        val currentMode = followMode[player.name] ?: false
        followMode[player.name] = !currentMode
        return !currentMode
    }
    // 添加新的任务
    private fun startDistanceCheckTask() {
        object : BukkitRunnable() {
            override fun run() {
                activePets.entries.toList().forEach { (playerName, cow) ->
                    val player = server.getPlayer(playerName) ?: return@forEach

                    // 检查牛是否在同一个世界
                    if (cow.world != player.world) {
                        handlePetTeleport(player, cow)
                        return@forEach
                    }

                    // 检查距离
                    val distance = cow.location.distance(player.location)
                    if (distance > TELEPORT_DISTANCE) {
                        handlePetTeleport(player, cow)
                    }
                }
            }
        }.runTaskTimer(this, 100L, 100L) // 每5秒检查一次
    }
    private fun startFollowTask() {
        object : BukkitRunnable() {
            override fun run() {
                activePets.entries.forEach { (playerName, cow) ->
                    val player = server.getPlayer(playerName) ?: return@forEach
                    if (followMode[playerName] == true) {
                        handleFollowBehavior(player, cow)
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L) // 每tick更新一次
    }
    private fun startLostPetCleanupTask() {
        object : BukkitRunnable() {
            override fun run() {
                lostPets.entries.removeAll { (playerName, cow) ->
                    if (!cow.isValid || cow.isDead) {
                        logger.info("Cleaned up lost pet for player $playerName")
                        true
                    } else {
                        // 尝试移除未被加载区块中的实体
                        if (!cow.location.chunk.isLoaded) {
                            cow.remove()
                            logger.info("Removed lost pet in unloaded chunk for player $playerName")
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }.runTaskTimer(this, 6000L, 6000L) // 每5分钟清理一次
    }
    private fun handlePetTeleport(player: Player, cow: Cow) {
        // 检查目标位置是否安全
        val targetLocation = findSafeLocation(player.location)
        if (targetLocation != null) {
            // 如果目标位置所在区块已加载，直接传送
            if (targetLocation.chunk.isLoaded) {
                cow.teleport(targetLocation)
                server.scheduler.runTaskLater(this, Runnable {
                    checkAndRespawnPet(player, cow)
                }, 5L) // 等待5tick后检查位置
//                player.sendMessage("${PREFIX}§a你的超级小母牛已传送到你身边！")
            } else {
                // 如果区块未加载，创建新的宠物并记录旧的
                lostPets[player.name] = cow
                activePets.remove(player.name)
                CompletableFuture.runAsync {
                    try {
                        // 在异步线程中加载区块
                        targetLocation.chunk.load(true)

                        // 返回主线程生成实体
                        server.scheduler.runTask(this, Runnable {
                            if (targetLocation.chunk.isLoaded) {
                                val newCow = player.world.spawnEntity(targetLocation, EntityType.COW) as Cow
                                val data = petData[player.name] ?: return@Runnable
                                updateCowStats(newCow, player.name, data)
                                activePets[player.name] = newCow
                                player.sendMessage("${PREFIX}§a由于原宠物所在区块未加载，已为你创建新的超级小母牛！")
                            } else {
                                player.sendMessage("${PREFIX}§c无法加载目标区块，请稍后重试！")
                            }
                        })
                    } catch (e: Exception) {
                        logger.warning("Failed to load chunk for pet teleport: ${e.message}")
                        player.sendMessage("${PREFIX}§c传送失败，请稍后重试！")
                    }
                }
                //
            }
        } else {
            forceRespawnAtPlayer(player)
//            player.sendMessage("${PREFIX}§c无法找到安全的传送位置！")
        }
    }
    private fun checkAndRespawnPet(player: Player, cow: Cow) {
        // 检查小母牛是否在玩家附近
        if (!cow.isValid || cow.isDead ||
            cow.location.distance(player.location) > 5.0 ||
            cow.world != player.world) {

            // 移除旧的宠物
            cow.remove()
            activePets.remove(player.name)

            // 尝试在安全位置重新生成
            val safeLocation = findSafeLocation(player.location)
            if (safeLocation != null) {
                tryRespawnPet(player, safeLocation)
            } else {
                forceRespawnAtPlayer(player)
            }
        }
    }
    private fun tryRespawnPet(player: Player, location: Location) {
        // 增加失败计数
        spawnFailCounts[player.name] = (spawnFailCounts[player.name] ?: 0) + 1

        if ((spawnFailCounts[player.name] ?: 0) >= MAX_SPAWN_FAILS) {
            // 如果失败次数过多，直接在玩家位置生成
            forceRespawnAtPlayer(player)
            return
        }

        // 尝试生成新的宠物
        try {
            val newCow = player.world.spawnEntity(location, EntityType.COW) as Cow
            val data = petData[player.name] ?: return

            updateCowStats(newCow, player.name, data)
            activePets[player.name] = newCow

            // 检查是否生成成功
            server.scheduler.runTaskLater(this, Runnable {
                if (!newCow.isValid || newCow.isDead ||
                    newCow.location.distance(player.location) > 5.0) {
                    // 生成失败，重试
                    newCow.remove()
                    activePets.remove(player.name)
                    tryRespawnPet(player, player.location)
                } else {
                    // 生成成功，重置失败计数
                    spawnFailCounts[player.name] = 0
                    player.sendMessage("${PREFIX}§a已为你重新生成超级小母牛！")
                    playRespawnEffects(newCow.location)
                }
            }, 5L)
        } catch (e: Exception) {
            logger.warning("Failed to spawn pet: ${e.message}")
            tryRespawnPet(player, player.location)
        }
    }
    private fun forceRespawnAtPlayer(player: Player) {
        // 直接在玩家位置生成
        val newCow = player.world.spawnEntity(player.location, EntityType.COW) as Cow
        val data = petData[player.name] ?: return

        updateCowStats(newCow, player.name, data)
        activePets[player.name] = newCow

        // 重置失败计数
        spawnFailCounts[player.name] = 0

        player.sendMessage("${PREFIX}§a已在你的位置强制生成超级小母牛！")
        playRespawnEffects(newCow.location)
    }
    private fun handleFollowBehavior(player: Player, cow: Cow) {
        val distance = cow.location.distance(player.location)
        if (distance > 2.0 && distance < MAX_FOLLOW_DISTANCE) {
            val direction = player.location.toVector().subtract(cow.location.toVector()).normalize()
            val speed = when {
                distance > 10 -> 0.4
                distance > 5 -> 0.3
                else -> 0.2
            }

            // 计算新的速度向量，保持Y轴速度以实现更自然的跟随
            val newVelocity = direction.multiply(speed)
            newVelocity.y = cow.velocity.y

            // 如果玩家在空中，给予跳跃效果
            if (player.location.y > cow.location.y + 1.0 && cow.isOnGround) {
                newVelocity.y = 0.5
            }

            cow.velocity = newVelocity
        }
    }

    private fun findSafeLocation(playerLoc: Location): Location? {
        val loc = playerLoc.clone()

        // 检查玩家位置后方2格的位置
        loc.add(playerLoc.direction.multiply(-2))

        // 确保位置安全
        for (y in 0..3) {
            val checkLoc = loc.clone().add(0.0, y.toDouble(), 0.0)
            if (isSafeLocation(checkLoc)) {
                return checkLoc
            }
        }

        // 如果没找到安全位置，尝试玩家周围的位置
        for (x in -2..2) {
            for (z in -2..2) {
                val checkLoc = playerLoc.clone().add(x.toDouble(), 0.0, z.toDouble())
                if (isSafeLocation(checkLoc)) {
                    return checkLoc
                }
            }
        }

        return null
    }

    private fun isSafeLocation(loc: Location): Boolean {
        val block = loc.block
        val above = loc.clone().add(0.0, 1.0, 0.0).block
        val below = loc.clone().add(0.0, -1.0, 0.0).block

        return !block.type.isSolid &&
                !above.type.isSolid &&
                below.type.isSolid &&
                !below.isLiquid
    }

    override fun onDisable() {
        // 保存数据前收回所有宠物
        activePets.forEach { (_, cow) ->
            cow.remove()
        }
        activePets.clear()
        respawnTasks.values.forEach { it.cancel() }
        respawnTasks.clear()
        respawnBossBars.values.forEach { it.removeAll() }
        respawnBossBars.clear()
        saveData()

        logger.info("SuperCow plugin has been disabled!")
        spawnFailCounts.clear()
    }

    private fun loadData() {
        config.getKeys(false).forEach { playerName ->
            val cowUUID = config.getString("$playerName.cowUUID") ?: return@forEach
            val level = config.getInt("$playerName.level", 1)
            val exp = config.getInt("$playerName.exp", 0)
            val kills = config.getInt("$playerName.kills", 0)
            petData[playerName] = SuperCowData(cowUUID, level, exp, kills)
        }
    }

    private fun saveData() {
        petData.forEach { (playerName, data) ->
            config.set("$playerName.cowUUID", data.cowUUID)
            config.set("$playerName.level", data.level)
            config.set("$playerName.exp", data.exp)
            config.set("$playerName.kills", data.kills)
        }
        config.save(dataFile)
    }

    fun summonPet(player: Player): Boolean {
        if (activePets.containsKey(player.name)) {
            player.sendMessage("${PREFIX}§c你的超级小母牛已经被召唤出来了！")
            return false
        }

        val data = petData[player.name] ?: run {
            player.sendMessage("${PREFIX}§c你还没有超级小母牛！")
            return false
        }

        val cow = player.world.spawnEntity(player.location, EntityType.COW) as Cow
        updateCowStats(cow, player.name, data)

        activePets[player.name] = cow
        player.sendMessage("${PREFIX}§a成功召唤超级小母牛！")
        return true
    }

    fun recallPet(player: Player): Boolean {
        val cow = activePets.remove(player.name)
        if (cow != null) {
            cow.remove()
            player.sendMessage("${PREFIX}§a成功收回超级小母牛！")
            return true
        }
        player.sendMessage("${PREFIX}§c你没有召唤出超级小母牛！")
        return false
    }

    fun getPetStatus(player: Player): String {
        val data = petData[player.name] ?: return "${PREFIX}§c你还没有超级小母牛！"
        val cow = activePets[player.name]

        return """
            §6=== 超级小母牛状态 ===
            §f等级: §a${data.level}
            §f经验值: §a${data.exp}/100
            §f击杀数: §a${data.kills}
            §f状态: §a${if (cow != null) "已召唤" else "未召唤"}
            §f生命值: §a${cow?.health?.toInt() ?: 0}/${cow?.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value?.toInt() ?: 0}
            §f攻击力: §a${5.0 + data.level}
            §6==================
        """.trimIndent()
    }

    fun getActivePets(): Map<String, Cow> = activePets
    fun getPetData(playerName: String): SuperCowData? = petData[playerName]

    fun addExpToPet(playerName: String, exp: Int) {
        val data = petData[playerName] ?: return
        val cow = activePets[playerName] ?: return

        data.exp += exp
        if (data.exp >= 100) {
            data.level++
            data.exp = 0
            updateCowStats(cow, playerName, data)
            server.getPlayer(playerName)?.sendMessage("${PREFIX}§a你的超级小母牛升级了！当前等级: ${data.level}")
        }
    }

    private fun updateCowStats(cow: Cow, playerName: String, data: SuperCowData) {
        cow.customName = "§6${playerName}的超级小母牛 §7[Lv.${data.level}]"
        cow.isCustomNameVisible = true
        cow.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.baseValue = 40.0 + (data.level * 5)
        cow.health = cow.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 40.0
        cow.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)?.baseValue = 0.3
        cow.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE)?.baseValue = 5.0 + data.level
    }

    private fun startTasks() {
        // 自动保存任务
        object : BukkitRunnable() {
            override fun run() {
                saveData()
            }
        }.runTaskTimer(this, 6000L, 6000L) // 每5分钟保存一次

        // 自动回血任务
        object : BukkitRunnable() {
            override fun run() {
                activePets.forEach { (_, cow) ->
                    if (cow.health < cow.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 40.0) {
                        cow.health = minOf(cow.health + 2.0,
                            cow.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 40.0)
                    }
                }
            }
        }.runTaskTimer(this, 0L, 100L)
    }

    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        val entity = event.rightClicked

        if (entity is Cow && player.inventory.itemInMainHand.type == Material.MILK_BUCKET) {
            event.isCancelled = true

            if (petData.containsKey(player.name)) {
                player.sendMessage("${PREFIX}§c你已经有一只超级小母牛了！")
                return
            }

            petData[player.name] = SuperCowData(entity.uniqueId.toString(), 1, 0, 0)
            entity.remove()

            player.inventory.itemInMainHand.type = Material.BUCKET
            player.sendMessage("${PREFIX}§a成功捕获超级小母牛！")
            summonPet(player)
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer
        if (killer is Player) {
            val pet = activePets[killer.name] ?: return
            if (pet.location.distance(event.entity.location) <= 8.0) {
                val data = petData[killer.name] ?: return
                data.kills++
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        respawnBossBars[player.name]?.removeAll()
        respawnBossBars.remove(player.name)
        // 玩家退出时自动收回宠物
        recallPet(event.player)
    }

    private fun startCombatCheckTask() {
        object : BukkitRunnable() {
            override fun run() {
                combatManager.checkAndUpdateTargets()
            }
        }.runTaskTimer(this, 20L, 20L) // 每秒检查一次
    }


}

data class SuperCowData(
    val cowUUID: String,
    var level: Int,
    var exp: Int,
    var kills: Int
)

class SuperCowCommand(private val plugin: SuperCow) : CommandExecutor, TabCompleter {
    private val subCommands = listOf("summon", "recall", "status", "follow", "version")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("${SuperCow.PREFIX}§c这个命令只能由玩家使用！")
            return true
        }

        if (args.isEmpty()) {
            showHelp(sender)
            return true
        }

        when (args[0].lowercase(Locale.getDefault())) {
            "summon" -> plugin.summonPet(sender)
            "recall" -> plugin.recallPet(sender)
            "status" -> sender.sendMessage(plugin.getPetStatus(sender))
            "version" -> sender.sendMessage("${SuperCow.PREFIX}§fv1.0 §7by YourName")
            "follow" -> {
                val followEnabled = plugin.toggleFollowMode(sender)
                sender.sendMessage(
                    if (followEnabled) "${SuperCow.PREFIX}§a已启用跟随模式"
                    else "${SuperCow.PREFIX}§c已禁用跟随模式"
                )
            }
            else -> showHelp(sender)
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String>? {
        if (sender !is Player) return null

        return when (args.size) {
            1 -> subCommands.filter { it.startsWith(args[0].lowercase()) }
            else -> null
        }
    }

    private fun showHelp(player: Player) {
        player.sendMessage("""
                §6=== SuperCow 命令帮助 ===
                §f/supercow summon §7- 召唤你的超级小母牛
                §f/supercow recall §7- 收回你的超级小母牛
                §f/supercow status §7- 查看你的超级小母牛状态
                §f/supercow follow §7- 切换跟随模式
                §f/supercow version §7- 查看插件版本
                §6=====================
            """.trimIndent())
    }
}