package net.dabbit.supercow.combat

import net.dabbit.supercow.SuperCow
import org.bukkit.*
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

import org.bukkit.event.EventHandler
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class SummonManager(
    private val plugin: SuperCow,
    private val trajectoryCalculator: ArrowTrajectoryCalculator
): Listener {
    val activeSummons = mutableMapOf<String, MutableList<LivingEntity>>()
    private val summonTasks = mutableMapOf<String, BukkitRunnable>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    object Config {
        const val SUMMON_COUNT = 10
        const val SUMMON_DURATION = 10 * 20L // 30秒
        const val ATTACK_COOLDOWN = 1L
        const val SHOOT_RANGE = 16.0
        const val BASE_DAMAGE = 8.0
        const val RAGE_DAMAGE = 16.0

        // 各种召唤物的属性
        object Cow {
            const val MAX_HEALTH = 20.0
            const val MOVEMENT_SPEED = 0.3
            const val FOLLOW_RANGE = 20.0
        }

        object Rabbit {
            const val MAX_HEALTH = 12.0
            const val MOVEMENT_SPEED = 0.5
            const val JUMP_STRENGTH = 0.8
        }
        object Parrot {
            const val MAX_HEALTH = 3.0
            const val MOVEMENT_SPEED = 0.4
            const val FLIGHT_HEIGHT = 5.0
            const val AVOIDANCE_RANGE = 3.0
            const val AVOIDANCE_STRENGTH = 0.5
            const val RANDOM_MOVEMENT = 0.1
        }

        object Arrow {
            const val CLEANUP_DELAY = 20L  // 5秒后清理箭矢
            const val BASE_DAMAGE = 8.0
            const val RAGE_DAMAGE = 18.0
        }
    }

    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        val clickedEntity = event.rightClicked

        // 检查是否是右键点击超级牛
        if (clickedEntity !is Cow || !plugin.getActivePets().containsValue(clickedEntity)) {
            return
        }

        // 检查手中物品是否为干草块
        val itemInHand = player.inventory.itemInMainHand
        if (itemInHand.type != Material.HAY_BLOCK) {
            return
        }

        // 获取当前目标
        val currentTarget = plugin.combatManager.getCurrentTarget(player.name)
        if (currentTarget == null) {
            player.sendMessage("[SUPER COW]§c需要先有攻击目标才能召唤！")
            return
        }

        // 随机选择召唤类型
        val summonType = SummonType.values().random()
        tryStartSummon(player, clickedEntity as Cow, currentTarget, summonType)

        // 阻止正常的交互事件
//        event.isCancelled = true
    }

    fun tryStartSummon(player: Player, mainCow: Cow, target: Entity, type: SummonType): Boolean {
        // 检查玩家是否有稻草
        if (!consumeHayBale(player)) {
            player.sendMessage("[SUPER COW]§c你需要一个稻草块来进行召唤！")
            return false
        }

        // 清理之前的召唤物
        clearSummons(player.name)

        // 开始召唤
        when (type) {
            SummonType.COWS -> summonCows(player, mainCow, target)
            SummonType.PARROTS -> summonParrots(player, mainCow, target)
            SummonType.RABBITS -> summonRabbits(player, mainCow, target)
        }

        // 播放召唤效果
        playSummonEffect(mainCow.location, type)
        return true
    }

    private fun consumeHayBale(player: Player): Boolean {
        return true;
        val hayBale = ItemStack(Material.HAY_BLOCK, 1)
        return if (player.inventory.containsAtLeast(hayBale, 1)) {
            player.inventory.removeItem(hayBale)
            true
        } else false
    }

    private fun summonCows(player: Player, mainCow: Cow, target: Entity) {
        val summons = mutableListOf<LivingEntity>()
        val location = mainCow.location

        repeat(Config.SUMMON_COUNT) {
            val offset = getRandomOffset()
            val spawnLoc = location.clone().add(offset)

            val cow = location.world?.spawn(spawnLoc, Cow::class.java)?.apply {
                customName = "§6${player.name}的超级牛军团"
                isCustomNameVisible = true
                getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH)?.baseValue = Config.Cow.MAX_HEALTH
                health = Config.Cow.MAX_HEALTH
                getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED)?.baseValue = Config.Cow.MOVEMENT_SPEED
                setAI(true)
            } ?: return@repeat  // 使用 return@repeat 代替 continue

            summons.add(cow)
        }

        activeSummons[player.name] = summons
        startSummonTask(player.name, target, SummonType.COWS)
    }

    private fun summonParrots(player: Player, mainCow: Cow, target: Entity) {
        val summons = mutableListOf<LivingEntity>()
        val location = mainCow.location.clone().add(0.0, Config.Parrot.FLIGHT_HEIGHT, 0.0)

        repeat(Config.SUMMON_COUNT) {
            val offset = getRandomOffset()
            val spawnLoc = location.clone().add(offset)

            val parrot = location.world?.spawn(spawnLoc, Parrot::class.java)?.apply {
                customName = "§b${player.name}的超级空军"
                isCustomNameVisible = true
                getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH)?.baseValue = Config.Parrot.MAX_HEALTH
                health = Config.Parrot.MAX_HEALTH
                getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED)?.baseValue = Config.Parrot.MOVEMENT_SPEED
                setAI(true)
            } ?: return@repeat  // 使用 return@repeat 代替 continue

            summons.add(parrot)
        }

        activeSummons[player.name] = summons
        startSummonTask(player.name, target, SummonType.PARROTS)
    }

    private fun summonRabbits(player: Player, mainCow: Cow, target: Entity) {
        val summons = mutableListOf<LivingEntity>()
        val location = mainCow.location

        repeat(Config.SUMMON_COUNT) {
            val offset = getRandomOffset()
            val spawnLoc = location.clone().add(offset)

            val rabbit = location.world?.spawn(spawnLoc, Rabbit::class.java)?.apply {
                customName = "§d${player.name}的超级兔子部队"
                isCustomNameVisible = true
                getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH)?.baseValue = Config.Rabbit.MAX_HEALTH
                health = Config.Rabbit.MAX_HEALTH
                getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED)?.baseValue = Config.Rabbit.MOVEMENT_SPEED
//                rabbitType = Rabbit.Type.THE_KILLER_BUNNY
                setAI(true)
            } ?: return@repeat  // 使用 return@repeat 代替 continue

            summons.add(rabbit)
        }

        activeSummons[player.name] = summons
        startSummonTask(player.name, target, SummonType.RABBITS)
    }

//    private fun startSummonTask(playerName: String, target: Entity, type: SummonType) {
//        summonTasks[playerName]?.cancel()
//
//        val task = object : BukkitRunnable() {
//            var ticks = 0
//            var attackTicks = 0
//
//            override fun run() {
//                ticks++
//                attackTicks++
//
//                // 立即检查目标状态
//                if (isTargetInvalid(target)) {
//                    plugin.server.scheduler.runTask(plugin, Runnable {
//                        clearSummons(playerName)
//                    })
//                    cancel()
//                    return
//                }
//
//                val summons = activeSummons[playerName] ?: return
//                if (summons.isEmpty()) {
//                    cancel()
//                    return
//                }
//
//                // 更新所有召唤物的行为
//                val iterator = summons.iterator()
//                while (iterator.hasNext()) {
//                    val summon = iterator.next()
//
//                    // 检查召唤物状态
//                    if (!summon.isValid || summon.isDead) {
//                        iterator.remove()
//                        continue
//                    }
//
//                    // 再次检查目标状态，确保在更新行为前目标仍然有效
//                    if (isTargetInvalid(target)) {
//                        plugin.server.scheduler.runTask(plugin, Runnable {
//                            clearSummons(playerName)
//                        })
//                        cancel()
//                        return
//                    }
//
//                    // 更新位置和行为
//                    when (type) {
//                        SummonType.PARROTS -> updateParrotBehavior(summon as Parrot, target)
//                        SummonType.RABBITS -> updateRabbitBehavior(summon as Rabbit, target)
//                        SummonType.COWS -> updateCowBehavior(summon as Cow, target)
//                    }
//
//                    // 处理攻击逻辑
//                    if (attackTicks >= Config.ATTACK_COOLDOWN) {
//                        when (type) {
//                            SummonType.PARROTS, SummonType.COWS -> {
//                                if (summon.location.distance(target.location) <= Config.SHOOT_RANGE) {
//                                    shootArrow(summon, target)
//                                }
//                            }
//                            SummonType.RABBITS -> {
//                                if (summon.location.distance(target.location) <= 2.0) {
//                                    (target as? LivingEntity)?.let { livingTarget ->
//                                        if (!isTargetInvalid(livingTarget)) {
//                                            livingTarget.damage(Config.BASE_DAMAGE, summon)
//                                        }
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//
//                if (attackTicks >= Config.ATTACK_COOLDOWN) {
//                    attackTicks = 0
//                }
//
//                // 检查持续时间
//                if (ticks >= Config.SUMMON_DURATION) {
//                    plugin.server.scheduler.runTask(plugin, Runnable {
//                        clearSummons(playerName)
//                    })
//                    cancel()
//                }
//            }
//        }
//
//        summonTasks[playerName] = task
//        task.runTaskTimer(plugin, 0L, 1L)
//    }

    private fun startSummonTask(playerName: String, target: Entity, type: SummonType) {
        summonTasks[playerName]?.cancel()

        val task = object : BukkitRunnable() {
            var ticks = 0
            var attackTicks = 0

            override fun run() {
                ticks++
                attackTicks++

                // 立即检查目标状态
                if (isTargetInvalid(target)) {
                    plugin.server.scheduler.runTask(plugin) {
                        clearSummons(playerName)
                    }
                    cancel()
                    return
                }

                val summons = activeSummons[playerName] ?: return
                if (summons.isEmpty()) {
                    cancel()
                    return
                }

                // 更新所有召唤物的行为
                val iterator = summons.iterator()
                while (iterator.hasNext()) {
                    val summon = iterator.next()

                    // 检查召唤物状态
                    if (!summon.isValid || summon.isDead) {
                        iterator.remove()
                        continue
                    }

                    // 再次检查目标状态，确保在更新行为前目标仍然有效
                    if (isTargetInvalid(target)) {
                        plugin.server.scheduler.runTask(plugin) {
                            clearSummons(playerName)
                        }
                        cancel()
                        return
                    }

                    // 更新位置和行为
                    when (type) {
                        SummonType.PARROTS -> updateParrotBehavior(summon as Parrot, target)
                        SummonType.RABBITS -> updateRabbitBehavior(summon as Rabbit, target)
                        SummonType.COWS -> updateCowBehavior(summon as Cow, target)
                    }

                    // 处理攻击逻辑
                    if (attackTicks >= Config.ATTACK_COOLDOWN) {
                        when (type) {
                            SummonType.PARROTS, SummonType.COWS -> {
                                if (summon.location.distance(target.location) <= Config.SHOOT_RANGE) {
                                    shootArrow(summon as LivingEntity, target, plugin)
                                }
                            }
                            SummonType.RABBITS -> {
                                if (summon.location.distance(target.location) <= 2.0) {
                                    (target as? LivingEntity)?.let { livingTarget ->
                                        if (!isTargetInvalid(livingTarget)) {
                                            livingTarget.damage(Config.BASE_DAMAGE, summon)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (attackTicks >= Config.ATTACK_COOLDOWN) {
                    attackTicks = 0
                }

                // 检查持续时间
                if (ticks >= Config.SUMMON_DURATION) {
                    plugin.server.scheduler.runTask(plugin) {
                        clearSummons(playerName)
                    }
                    cancel()
                }
            }
        }

        summonTasks[playerName] = task
        task.runTaskTimer(plugin, 0L, 1L)
    }


    // 添加一个辅助方法来检查目标状态
    private fun isTargetInvalid(target: Entity): Boolean {
        return !target.isValid ||
                target.isDead ||
                (target as? LivingEntity)?.health?.let { it <= 0 } ?: false
    }

    private fun updateParrotBehavior(parrot: Parrot, target: Entity) {
        val targetLoc = target.location.clone().add(0.0, Config.Parrot.FLIGHT_HEIGHT, 0.0)

        // 添加避让逻辑
        val avoidance = Vector(0.0, 0.0, 0.0)
        parrot.world.getNearbyEntities(parrot.location, 3.0, 3.0, 3.0)
            .filterIsInstance<LivingEntity>()
            .filter { it != parrot && it != target }
            .forEach { other ->
                val away = parrot.location.toVector().subtract(other.location.toVector())
                val distance = away.length()
                if (distance < 3.0) {
                    avoidance.add(away.normalize().multiply(3.0 - distance))
                }
            }

        // 计算最终方向（目标方向 + 避让向量）
        val direction = targetLoc.clone().subtract(parrot.location).toVector().normalize()
        val finalDirection = direction.add(avoidance.multiply(0.5)).normalize()

        // 添加随机偏移，使运动更自然
        finalDirection.add(Vector(
            Random.nextDouble(-0.1, 0.1),
            Random.nextDouble(-0.1, 0.1),
            Random.nextDouble(-0.1, 0.1)
        ))

        parrot.velocity = finalDirection.multiply(Config.Parrot.MOVEMENT_SPEED)
    }

    private fun updateRabbitBehavior(rabbit: Rabbit, target: Entity) {
        val direction = target.location.clone().subtract(rabbit.location).toVector()

        // 添加避让逻辑
        val avoidance = Vector(0.0, 0.0, 0.0)
        rabbit.world.getNearbyEntities(rabbit.location, 2.0, 2.0, 2.0)
            .filterIsInstance<LivingEntity>()
            .filter { it != rabbit && it != target }
            .forEach { other ->
                val away = rabbit.location.toVector().subtract(other.location.toVector())
                val distance = away.length()
                if (distance < 2.0) {
                    avoidance.add(away.normalize().multiply(2.0 - distance))
                }
            }

        // 合并方向和跳跃
        if (rabbit.isOnGround && Random.nextDouble() < 0.1) {
            val finalDirection = direction.normalize().add(avoidance).normalize()
            rabbit.velocity = finalDirection.multiply(Config.Rabbit.MOVEMENT_SPEED)
                .add(Vector(0.0, Config.Rabbit.JUMP_STRENGTH, 0.0))
        }
    }

    private fun updateCowBehavior(cow: Cow, target: Entity) {
        val direction = target.location.clone().subtract(cow.location).toVector()

        // 添加避让其他召唤物的逻辑
        val avoidance = Vector(0.0, 0.0, 0.0)
        cow.world.getNearbyEntities(cow.location, 2.0, 2.0, 2.0)
            .filterIsInstance<LivingEntity>()
            .filter { it != cow && it != target }
            .forEach { other ->
                val away = cow.location.toVector().subtract(other.location.toVector())
                val distance = away.length()
                if (distance < 2.0) {
                    avoidance.add(away.normalize().multiply(2.0 - distance))
                }
            }

        // 合并目标方向和避让向量
        val finalDirection = direction.normalize().add(avoidance).normalize()
        cow.velocity = finalDirection.multiply(Config.Cow.MOVEMENT_SPEED)
    }

//    private fun shootArrow(shooter: LivingEntity, target: Entity) {
//        val shootPos = shooter.location.clone().add(0.0, 1.5, 0.0)
//        val velocity = trajectoryCalculator.calculateArrowVelocity(
//            shootPos,
//            target.location,
//            1.5
//        )
//
//        val arrow = shooter.world.spawnArrow(
//            shootPos,
//            velocity,
//            1.5f,
//            0f
//        ).apply {
//            this.shooter = shooter
//            damage = Config.BASE_DAMAGE
//            isCritical = true
//            setMetadata("summon_arrow", plugin.fixedMetadataValue())
//
//            // 添加箭矢清理任务
//            object : BukkitRunnable() {
//                override fun run() {
//                    if (isValid && !isDead) {
//                        remove()
//                    }
//                }
//            }.runTaskLater(plugin, 100L) // 5秒后清理
//        }
//
//        playShootEffect(shooter.location)
//    }


    private fun shootArrow(shooter: LivingEntity, target: Entity, plugin: SuperCow) {
        val shootPos = shooter.location.clone().add(0.0, 1.5, 0.0)

        // 预测目标位置
        val targetPos = predictTargetPosition(target)

        // 计算射击参数
        val (velocity, shootAngle) = calculateShootParameters(shootPos, targetPos)

        // 生成箭矢
        val arrow = shooter.world.spawnArrow(
            shootPos,
            velocity,
            1.5f,
            shootAngle
        ) as Arrow

        arrow.shooter = shooter

        // 通过给射击实体的弓添加附魔来增强箭矢伤害
        val bow = ItemStack(org.bukkit.Material.BOW)
        val meta = bow.itemMeta
        if (meta != null) {
            meta.addEnchant(Enchantment.ARROW_DAMAGE, (Config.BASE_DAMAGE / 2).toInt(), true)
        }
        bow.itemMeta = meta

        // 虽然实际中射击实体不一定手持弓，但可以模拟这种效果
        arrow.knockbackStrength = 1
        arrow.isCritical = true
        arrow.setMetadata("summon_arrow", plugin.fixedMetadataValue())

        // 设置箭矢的其他属性
//        arrow.pickupStatus = Arrow.PickupStatus.DISALLOWED

        // 添加箭矢清理任务
        object : BukkitRunnable() {
            override fun run() {
                if (arrow.isValid && !arrow.isDead) {
                    arrow.remove()
                }
            }
        }.runTaskLater(plugin, 20L)

        playShootEffect(shooter.location)
    }


    private fun predictTargetPosition(target: Entity): Location {
        val targetLoc = target.location.clone()
        val targetVelocity = target.velocity

        // 预测时间（ticks）- 可以根据距离动态调整
        val predictionTime = 10

        // 考虑目标的移动方向和速度
        targetLoc.add(
            targetVelocity.x * predictionTime,
            targetVelocity.y * predictionTime + 0.5, // 稍微往上瞄准一点
            targetVelocity.z * predictionTime
        )

        return targetLoc
    }

    private fun calculateShootParameters(shootPos: Location, targetPos: Location): Pair<Vector, Float> {
        // 计算基础方向向量
        val direction = targetPos.clone().subtract(shootPos).toVector()

        // 计算水平距离
        val horizontalDistance = Math.sqrt(direction.x * direction.x + direction.z * direction.z)

        // 计算所需的仰角
        val gravity = 0.05 // Minecraft的重力加速度
        val velocityXZ = 1.5 // 水平速度
        val velocityY = direction.y / horizontalDistance + gravity * horizontalDistance / (2 * velocityXZ)

        // 标准化方向向量
        direction.normalize()

        // 应用计算后的速度分量
        val velocity = Vector(
            direction.x * velocityXZ,
            velocityY,
            direction.z * velocityXZ
        )

        // 计算扩散角度（根据距离调整）
        val spread = when {
            horizontalDistance < 5 -> 0f
            horizontalDistance < 10 -> 1f
            else -> 2f
        }

        return Pair(velocity, spread)
    }

    private fun getModifiedVelocity(baseVelocity: Vector, distance: Double): Vector {
        // 根据距离调整速度
        val speedMultiplier = when {
            distance < 5 -> 1.2
            distance < 10 -> 1.5
            distance < 15 -> 1.8
            else -> 2.0
        }

        return baseVelocity.multiply(speedMultiplier)
    }

    // 可以添加一个帮助方法来计算两点间的距离
    private fun getDistance(loc1: Location, loc2: Location): Double {
        return loc1.distance(loc2)
    }
    @EventHandler
    fun onArrowHit(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager !is Arrow || !damager.hasMetadata("summon_arrow")) {
            return
        }

        val shooter = damager.shooter as? LivingEntity ?: return
        val victim = event.entity

        // 检查是否是队友伤害
//        if (isFriendlyFire(shooter, victim)) {
//            event.isCancelled = true
//            damager.remove()
//            return
//        }

        // 设置伤害值
        event.damage = Config.BASE_DAMAGE

        // 确保箭矢移除
        damager.remove()
    }



    // 检查是否是队友伤害
    private fun isFriendlyFire(attacker: LivingEntity, victim: Entity): Boolean {
        // 如果目标是召唤物或超级牛，取消伤害
        return activeSummons.values.any { summons ->
            summons.contains(victim) || summons.contains(attacker)
        } || plugin.getActivePets().containsValue(victim)
    }

    private fun getRandomOffset(): Vector {
        val angle = Random.nextDouble() * 2 * Math.PI
        val radius = Random.nextDouble(3.0, 6.0) // 增加范围，让召唤物更分散
        return Vector(
            cos(angle) * radius,
            0.0,
            sin(angle) * radius
        )
    }

    private fun playSummonEffect(location: Location, type: SummonType) {
        location.world?.apply {
            // 播放音效
            val sound = when (type) {
                SummonType.COWS -> Sound.ENTITY_COW_AMBIENT
                SummonType.PARROTS -> Sound.ENTITY_PARROT_AMBIENT
                SummonType.RABBITS -> Sound.ENTITY_RABBIT_AMBIENT
            }
            playSound(location, sound, 1.0f, 1.0f)

            // 播放粒子效果
            val particle = when (type) {
                SummonType.COWS -> Particle.VILLAGER_HAPPY
                SummonType.PARROTS -> Particle.END_ROD
                SummonType.RABBITS -> Particle.HEART
            }

            // 创建圆形粒子效果
            for (i in 0 until 360 step 10) {
                val angle = Math.toRadians(i.toDouble())
                val x = cos(angle) * 3
                val z = sin(angle) * 3
                spawnParticle(
                    particle,
                    location.clone().add(x, 0.5, z),
                    5,
                    0.2, 0.2, 0.2,
                    0.05
                )
            }
        }
    }

    private fun playShootEffect(location: Location) {
        location.world?.apply {
            playSound(location, Sound.ENTITY_ARROW_SHOOT, 0.5f, 1.2f)
            spawnParticle(
                Particle.CRIT,
                location.clone().add(0.0, 1.5, 0.0),
                5,
                0.1, 0.1, 0.1,
                0.05
            )
        }
    }

    fun clearSummons(playerName: String) {
        activeSummons[playerName]?.forEach { summon ->
            if (summon.isValid) {
                // 播放消失效果
                summon.world.apply {
                    // 增加更多粒子效果
                    spawnParticle(
                        Particle.SMOKE_NORMAL,
                        summon.location,
                        15,  // 增加粒子数量
                        0.3, 0.3, 0.3,  // 增加扩散范围
                        0.05
                    )
                    spawnParticle(
                        Particle.PORTAL,
                        summon.location,
                        10,
                        0.2, 0.2, 0.2,
                        0.05
                    )

                    // 播放更明显的音效组合
                    playSound(
                        summon.location,
                        Sound.BLOCK_GLASS_BREAK,
                        0.5f,
                        1.2f
                    )
                    playSound(
                        summon.location,
                        Sound.ENTITY_COW_AMBIENT,
                        0.3f,
                        1.5f
                    )
                }
                summon.remove()
            }
        }
        activeSummons.remove(playerName)
        summonTasks[playerName]?.cancel()
        summonTasks.remove(playerName)
    }

    enum class SummonType {
        COWS, PARROTS, RABBITS
    }

    fun onDisable() {
        activeSummons.forEach { (_, summons) ->
            summons.forEach { summon ->
                if (summon.isValid) summon.remove()
            }
        }
        activeSummons.clear()
        summonTasks.values.forEach { it.cancel() }
        summonTasks.clear()

        Bukkit.getWorlds().forEach { world ->
            world.entities
                .filterIsInstance<Arrow>()
                .filter { it.hasMetadata("summon_arrow") }
                .forEach { it.remove() }
        }
    }
}