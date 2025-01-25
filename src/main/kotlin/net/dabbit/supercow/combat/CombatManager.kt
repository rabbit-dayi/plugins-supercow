package net.dabbit.supercow.combat

import net.dabbit.supercow.pathfinding.PathfindingManager
import net.dabbit.supercow.SuperCow
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.random.Random

class CombatManager(
    private val plugin: SuperCow,
    private val pathfindingManager: PathfindingManager,
    private val effectManager: ArrowEffectManager,
    private val targetValidator: TargetValidator,
    private val trajectoryCalculator: ArrowTrajectoryCalculator,
    private val fireworkManager: FireworkManager,
    private val projectileManager: ProjectileManager,
    private val summonManager: SummonManager,
    private val explodingCowManager: ExplodingCowManager,
    private val chickenBombardmentManager: ChickenBombardmentManager,
    private val musicAttackManager: MusicAttackManager

) : Listener {
    private val currentTargets = mutableMapOf<String, MutableSet<Entity>>()
    private val attackCooldowns = mutableMapOf<String, Long>()
    private val arrowTasks = mutableMapOf<String, BukkitRunnable>()
    private val glowingTargets = mutableMapOf<String, MutableSet<Entity>>()
    private val rageMode = mutableMapOf<String, Boolean>()


    companion object {
        private const val ATTACK_COOLDOWN = 50L
        private const val SHOOT_RANGE = 16.0
        private const val RAGE_MODE_THRESHOLD = 0.3 // 30%血量以下进入狂暴
    }



    @EventHandler
    fun onPlayerAttackEntity(event: EntityDamageByEntityEvent) {
        val player = extractAttackingPlayer(event) ?: return

        val cow = plugin.getActivePets()[player.name] ?: return
        val target = event.entity

        if (!isValidAttackTarget(target)) return

        // 添加目标
        addTargets(player.name, target, cow)
    }

    @EventHandler
    fun onPlayerGetDamaged(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        val cow = plugin.getActivePets()[player.name] ?: return

        val attacker = extractAttacker(event) ?: return

        if (!isValidAttackTarget(attacker)) return

        // 添加目标
        addTargets(player.name, attacker, cow)
    }

    @EventHandler
    fun onCowGetDamaged(event: EntityDamageByEntityEvent) {
        val cow = event.entity as? Cow ?: return

        // 检查这头牛是否是超级牛宠物
        val ownerName = plugin.getActivePets().entries.find { it.value == cow }?.key ?: return

        val attacker = extractAttacker(event) ?: return

        if (!isValidAttackTarget(attacker)) return

        // 添加目标
        addTargets(ownerName, attacker, cow)
    }

    private fun extractAttackingPlayer(event: EntityDamageByEntityEvent): Player? {
        // 获取攻击者是否为玩家或由玩家发起
        return when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
    }

    private fun extractAttacker(event: EntityDamageByEntityEvent): Entity? {
        // 获取攻击者，可处理直接或间接攻击
        return when (val damager = event.damager) {
            is Projectile -> damager.shooter as? Entity
            else -> damager
        }
    }


    private fun addTargets(ownerName: String, primaryTarget: Entity, cow: Cow) {
        val targets = mutableSetOf<Entity>()
        targets.add(primaryTarget)

        if (!isFriendlyTarget(primaryTarget, ownerName)) {
            targets.add(primaryTarget)
        }

        // 搜索周围实体并添加到目标
//        cow.location.world?.getNearbyEntities(cow.location, SHOOT_RANGE, SHOOT_RANGE, SHOOT_RANGE)
//            ?.filter { isValidAttackTarget(it) && it != primaryTarget }
//            ?.take(MAX_TARGETS - 1)
//            ?.forEach { targets.add(it) }

        // 更新目标
        setNewTargets(ownerName, targets, cow)
    }



    @EventHandler
    fun onArrowHit(event: ProjectileHitEvent) {
        val arrow = event.entity as? Arrow ?: return
        if (!arrow.hasMetadata("supercow_arrow")) return

        val shooter = arrow.shooter as? Cow ?: return
        val ownerName = plugin.getActivePets().entries.find { it.value == shooter }?.key ?: return
        val isRageMode = rageMode[ownerName] ?: false

        effectManager.playHitEffects(event.entity.location, isRageMode)
        arrow.remove()
    }

    private fun setNewTargets(ownerName: String, newTargets: Set<Entity>, cow: Cow) {
        // 移除旧目标的高亮和寻路
        currentTargets[ownerName]?.forEach { oldTarget ->
            if (!newTargets.contains(oldTarget)) {
                stopGlowing(ownerName, oldTarget)
                pathfindingManager.stopPathfinding(cow)
            }
        }

        // 设置新目标
        currentTargets[ownerName] = newTargets.toMutableSet()

        // 选择主要目标进行寻路
        val primaryTarget = newTargets.firstOrNull()
        if (primaryTarget != null) {
            pathfindingManager.startPathfinding(cow, primaryTarget)
        }

        newTargets.forEach { target ->
            startGlowing(ownerName, target)
            startShooting(ownerName, cow)
        }

        checkRageMode(cow, ownerName)
    }
    private fun checkRageMode(cow: Cow, ownerName: String) {
        val maxHealth = cow.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
        val currentHealth = cow.health
        val healthPercentage = currentHealth / maxHealth

        rageMode[ownerName] = healthPercentage <= RAGE_MODE_THRESHOLD
    }

    private fun startShooting(ownerName: String, cow: Cow) {
        stopShooting(ownerName)

        val shootTask = object : BukkitRunnable() {
            override fun run() {
                val targets = currentTargets[ownerName] ?: return
                val iterator = targets.iterator()

                while (iterator.hasNext()) {
                    val target = iterator.next()
                    if (!isValidAttackTarget(target) ||
                        !cow.isValid ||
                        cow.location.distance(target.location) > SHOOT_RANGE
                    ) {
                        stopGlowing(ownerName, target)
                        iterator.remove()
                        continue
                    }

                    // 让牛面向目标
                    faceCowToTarget(cow, target.location)
                    shootArrow(cow, target, ownerName)
                }

                if (targets.isEmpty()) {
                    stopShooting(ownerName)
                }
            }
        }

        arrowTasks[ownerName] = shootTask
        // 根据目标血量调整攻击速度
        val attackSpeed = calculateAttackSpeed(currentTargets[ownerName]?.firstOrNull())
        shootTask.runTaskTimer(plugin, 0L, attackSpeed)
    }
    private fun calculateAttackSpeed(target: Entity?): Long {
        if (target !is LivingEntity) return 10L  // 基础攻击速度从20L降到10L

        val maxHealth = target.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
        val currentHealth = target.health
        val healthPercentage = currentHealth / maxHealth

        // 血量越低攻击速度越快，最低3tick
        return (10L * healthPercentage).toLong().coerceAtLeast(3L)
    }
    private fun faceCowToTarget(cow: Cow, targetLocation: Location) {
        val direction = targetLocation.clone().subtract(cow.location).toVector()
        cow.location.direction = direction
    }

    private fun stopShooting(ownerName: String) {
        arrowTasks[ownerName]?.cancel()
        arrowTasks.remove(ownerName)
    }

//    // CombatManager.kt 中的 shootArrow 函数修改
//    private fun shootArrow(cow: Cow, target: Entity, ownerName: String) {
//
//    }
    fun getCurrentTarget(playerName: String): Entity? {
        val target = currentTargets[playerName]?.firstOrNull() ?: return null
        val cow = plugin.getActivePets()[playerName] ?: return null

        // 检查是否是有效目标且在射程内
        if (!isValidAttackTarget(target) || target.location.distance(cow.location) > SHOOT_RANGE) {
            return null
        }

        // 检查是否是友军
        if (isFriendlyTarget(target, playerName)) {
            clearCombatTarget(playerName)
            return null
        }

        return target
    }
    private fun isFriendlyTarget(target: Entity, playerName: String): Boolean {
        // 检查是否是超级牛宠物
        if (plugin.getActivePets().containsValue(target)) {
            return false
        }

        // 检查是否是召唤物
        val summonManager = plugin.summonmgr
        return summonManager.activeSummons.any {
            (ownerName, summons) ->
            // 检查目标是否是任何玩家的召唤物
            summons.contains(target)
        }
    }


    private fun playShootEffects(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            playSound(location, Sound.ENTITY_ARROW_SHOOT, 1.0f, if (isRageMode) 1.5f else 1.0f)

            if (isRageMode) {
                // 狂暴模式粒子效果
                spawnParticle(
                    Particle.EXPLOSION_LARGE,
                    location.clone().add(0.0, 1.5, 0.0),
                    3,
                    0.2, 0.2, 0.2,
                    0.1
                )
            } else {
                // 普通模式粒子效果
                spawnParticle(
                    Particle.CRIT,
                    location.clone().add(0.0, 1.5, 0.0),
                    10,
                    0.2, 0.2, 0.2,
                    0.1
                )
            }
        }
    }

    private fun isValidAttackTarget(entity: Entity?): Boolean {
        if (entity == null) return false

        return when (entity) {
            is Player -> {
                !(entity.isDead ||
                        !entity.isValid ||
                        entity.isInvulnerable ||
                        entity.gameMode in arrayOf(GameMode.CREATIVE, GameMode.SPECTATOR))
            }
            is LivingEntity -> {
                !(entity.isDead ||
                        !entity.isValid ||
                        entity.isInvulnerable ||
                        entity is ArmorStand ||
                        entity is Villager ||
                        entity is WanderingTrader ||
                        (entity is Tameable && entity.isTamed))
            }
            else -> false
        }
    }

    private fun startGlowing(ownerName: String, target: Entity) {
        val targets = glowingTargets.getOrPut(ownerName) { mutableSetOf() }
        if (!targets.contains(target)) {
            target.isGlowing = true
            targets.add(target)
        }
    }

    private fun stopGlowing(ownerName: String, target: Entity) {
        glowingTargets[ownerName]?.let { targets ->
            if (targets.remove(target)) {
                target.isGlowing = false
            }
            if (targets.isEmpty()) {
                glowingTargets.remove(ownerName)
            }
        }
    }

    fun checkAndUpdateTargets() {
        val iterator = currentTargets.entries.iterator()
        while (iterator.hasNext()) {
            val (playerName, targets) = iterator.next()
            val cow = plugin.getActivePets()[playerName] ?: continue

            // 使用内部迭代器来安全地移除无效目标
            val targetIterator = targets.iterator()
            var primaryTargetRemoved = false
            var firstTarget = targets.firstOrNull()

            while (targetIterator.hasNext()) {
                val target = targetIterator.next()
                if (!isValidAttackTarget(target) ||
                    cow.location.distance(target.location) > SHOOT_RANGE
                ) {
                    stopGlowing(playerName, target)
                    if (target == firstTarget) {
                        primaryTargetRemoved = true
                    }
                    targetIterator.remove()
                }
            }

            // 如果主要目标被移除，更新寻路目标
            if (primaryTargetRemoved && targets.isNotEmpty()) {
                val newPrimaryTarget = targets.first()
                pathfindingManager.startPathfinding(cow, newPrimaryTarget)
            }

            // 如果该玩家的所有目标都被移除，则停止射击和寻路
            if (targets.isEmpty()) {
                stopShooting(playerName)
                pathfindingManager.stopPathfinding(cow)
                iterator.remove()
            }
        }
    }




    // 配置对象
    object FireworkConfig {
        const val MAX_LIFETIME_TICKS = 100
        const val TRACKING_SPEED = 0.2
        const val DETONATION_DISTANCE = 2.0
        const val BASE_VELOCITY = 1.0
        const val RAGE_VELOCITY = 1.5
    }


    // 生成随机颜色列表
    private fun generateRandomColors(count: Int): List<Color> {
        val baseColors = listOf(
            Color.RED,
            Color.BLUE,
            Color.GREEN,
            Color.YELLOW,
            Color.PURPLE,
            Color.AQUA,
            Color.FUCHSIA,
            Color.ORANGE,
            Color.LIME,
            Color.MAROON,
            Color.NAVY,
            Color.OLIVE,
            Color.TEAL
        )

        return buildList {
            repeat(count) {
                // 随机选择一个基础颜色并稍微改变它的色值
                val baseColor = baseColors.random()
                add(Color.fromRGB(
                    (baseColor.red + (-20..20).random()).coerceIn(0..255),
                    (baseColor.green + (-20..20).random()).coerceIn(0..255),
                    (baseColor.blue + (-20..20).random()).coerceIn(0..255)
                ))
            }
        }
    }


    fun clearCombatTarget(playerName: String) {
        val cow = plugin.getActivePets()[playerName] ?: return

        currentTargets[playerName]?.forEach { target ->
            stopGlowing(playerName, target)
        }
        currentTargets.remove(playerName)
        stopShooting(playerName)
        rageMode.remove(playerName)

        // 停止寻路
        pathfindingManager.stopPathfinding(cow)
    }

//private fun shootArrow(cow: Cow, target: Entity, ownerName: String) {
//    val currentTime = System.currentTimeMillis()
//    val lastAttackTime = attackCooldowns[cow.uniqueId.toString()] ?: 0L
//    val owner = plugin.server.getPlayer(ownerName) ?: return
//
//    if (!targetValidator.isValidTarget(target, owner)) return
//
//    val isRageMode = rageMode[ownerName] ?: false
//    val actualCooldown = if (isRageMode) ATTACK_COOLDOWN / 2 else ATTACK_COOLDOWN
//
//    if (currentTime - lastAttackTime < actualCooldown) return
//
//    attackCooldowns[cow.uniqueId.toString()] = currentTime
//
//    // 修改攻击方式选择，添加爆炸牛选项
//    val attackType = if (isRageMode) {
//        when (Random.nextInt(1000)) { // 使用1000来获得更精确的概率控制
//            in 0..849 -> "arrow"     // 85%
//            in 850..879 -> "firework" // 3%
//            in 880..909 -> "potion"   // 3%
//            in 910..939 -> "fireball" // 3%
//            in 940..959 -> "exploding_cow" // 2%
//            in 960..973 -> "summon_cows"    // 1.4%
//            in 974..987 -> "summon_parrots" // 1.4%
//            else -> "summon_rabbits"        // 1.2%
//        }
//    } else {
//        when (Random.nextInt(1000)) {
//            in 0..849 -> "arrow"     // 85%
//            in 850..879 -> "firework" // 3%
//            in 880..909 -> "potion"   // 3%
//            in 910..939 -> "fireball" // 3%
//            in 940..959 -> "exploding_cow" // 2%
//            else -> {                 // 4%
//                when (Random.nextInt(10)) {
//                    in 0..3 -> "summon_cows"    // 1.6%
//                    in 4..7 -> "summon_parrots" // 1.6%
//                    else -> "summon_rabbits"     // 0.8%
//                }
//            }
//        }
//    }
//
//    when (attackType) {
//        "arrow" -> {
//            val arrowLocation = cow.location.add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0)
//            val arrowCount = if (isRageMode) 3 else 1  // 狂暴模式发射3支箭
//
//            repeat(arrowCount) {
//                val arrow = cow.world.spawnEntity(arrowLocation, EntityType.ARROW) as Arrow
//
//                // 设置箭矢属性
//                arrow.shooter = cow
//                arrow.setMetadata("supercow_arrow", plugin.fixedMetadataValue())
//                arrow.damage = if (isRageMode) ArrowConfig.RAGE_DAMAGE else ArrowConfig.BASE_DAMAGE
//                arrow.isCritical = true
//
//                // 计算发射方向
//                val direction = target.location.clone().add(0.0, 0.5, 0.0)
//                    .subtract(arrowLocation).toVector().normalize()
//
//                // 添加随机偏移
//                val spread = if (isRageMode) ArrowConfig.RAGE_SPREAD else ArrowConfig.ARROW_SPREAD
//                if (spread > 0) {
//                    direction.add(
//                        Vector(
//                            Random.nextDouble(-spread, spread),
//                            Random.nextDouble(-spread, spread),
//                            Random.nextDouble(-spread, spread)
//                        )
//                    )
//                }
//
//                // 设置箭矢速度
//                val speed = if (isRageMode) ArrowConfig.RAGE_ARROW_SPEED else ArrowConfig.ARROW_SPEED
//                arrow.velocity = direction.multiply(speed)
//
//                // 粒子效果
//                arrow.world.spawnParticle(
//                    if (isRageMode) Particle.CRIT_MAGIC else Particle.CRIT,
//                    arrow.location,
//                    10,
//                    0.1, 0.1, 0.1,
//                    0.05
//                )
//            }
//        }
//        "firework" -> fireworkManager.shootFirework(cow.location, target, isRageMode)
//        "potion" -> projectileManager.shootPotion(cow.location, target, isRageMode)
//        "fireball" -> projectileManager.shootFireball(cow.location, target, isRageMode)
//        "exploding_cow" -> explodingCowManager.launchExplodingCow(cow.location, target, isRageMode)
//        "summon_cows" -> summonManager.tryStartSummon(owner, cow, target, SummonManager.SummonType.COWS)
//        "summon_parrots" -> summonManager.tryStartSummon(owner, cow, target, SummonManager.SummonType.PARROTS)
//        "summon_rabbits" -> summonManager.tryStartSummon(owner, cow, target, SummonManager.SummonType.RABBITS)
//    }
//
//    effectManager.playShootEffects(cow.location, isRageMode)
//    cow.health = (cow.health + ArrowConfig.HEAL_AMOUNT).coerceAtMost(cow.maxHealth)
//}

    private fun shootArrow(cow: Cow, target: Entity, ownerName: String) {
        val currentTime = System.currentTimeMillis()
        val lastAttackTime = attackCooldowns[cow.uniqueId.toString()] ?: 0L
        val owner = plugin.server.getPlayer(ownerName) ?: return

        if (!targetValidator.isValidTarget(target, owner)) return

        val isRageMode = rageMode[ownerName] ?: false
        val actualCooldown = if (isRageMode) ATTACK_COOLDOWN / 2 else ATTACK_COOLDOWN

        if (currentTime - lastAttackTime < actualCooldown) return

        attackCooldowns[cow.uniqueId.toString()] = currentTime

        // 修改后的攻击类型选择，大幅提高arrow概率
        val attackType = if (isRageMode) {
            when (Random.nextInt(1000)) {
                in 0..899 -> "arrow"      // 90%
                in 900..929 -> "firework"  // 3%
                in 930..959 -> "potion"    // 3%
                in 960..974 -> "fireball"  // 1.5%
                in 975..984 -> "exploding_cow" // 1%
                in 985..989 -> "chicken_bomb"  // 0.5%
                in 990..994 -> "music_attack"  // 0.5%
                in 995..996 -> "summon_cows"   // 0.2%
                in 997..998 -> "summon_parrots" // 0.2%
                else -> "summon_rabbits"       // 0.1%
            }
        } else {
            when (Random.nextInt(1000)) {
                in 0..949 -> "arrow"      // 95%
                in 950..964 -> "firework"  // 1.5%
                in 965..979 -> "potion"    // 1.5%
                in 980..984 -> "fireball"  // 0.5%
                in 985..989 -> "exploding_cow" // 0.5%
                in 990..992 -> "chicken_bomb"  // 0.3%
                in 993..995 -> "music_attack"  // 0.3%
                in 996..997 -> "summon_cows"   // 0.2%
                in 998..998 -> "summon_parrots" // 0.1%
                else -> "summon_rabbits"       // 0.1%
            }
        }

        when (attackType) {
            "arrow" -> {
                val arrowLocation = cow.location.add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0)
                val arrowCount = if (isRageMode) 3 else 1

                repeat(arrowCount) {
                    val arrow = cow.world.spawnEntity(arrowLocation, EntityType.ARROW) as Arrow

                    arrow.shooter = cow
                    arrow.setMetadata("supercow_arrow", plugin.fixedMetadataValue())
                    arrow.damage = if (isRageMode) ArrowConfig.RAGE_DAMAGE else ArrowConfig.BASE_DAMAGE
                    arrow.isCritical = true

                    val direction = target.location.clone().add(0.0, 0.5, 0.0)
                        .subtract(arrowLocation).toVector().normalize()

                    val spread = if (isRageMode) ArrowConfig.RAGE_SPREAD else ArrowConfig.ARROW_SPREAD
                    if (spread > 0) {
                        direction.add(
                            Vector(
                                Random.nextDouble(-spread, spread),
                                Random.nextDouble(-spread, spread),
                                Random.nextDouble(-spread, spread)
                            )
                        )
                    }

                    val speed = if (isRageMode) ArrowConfig.RAGE_ARROW_SPEED else ArrowConfig.ARROW_SPEED
                    arrow.velocity = direction.multiply(speed)

                    arrow.world.spawnParticle(
                        if (isRageMode) Particle.CRIT_MAGIC else Particle.CRIT,
                        arrow.location,
                        10,
                        0.1, 0.1, 0.1,
                        0.05
                    )
                }
            }
            "firework" -> fireworkManager.shootFirework(cow.location, target, isRageMode)
            "potion" -> projectileManager.shootPotion(cow.location, target, isRageMode)
            "fireball" -> projectileManager.shootFireball(cow.location, target, isRageMode)
            "exploding_cow" -> projectileManager.shootPotion(cow.location, target, isRageMode)
            "chicken_bomb" -> chickenBombardmentManager.startChickenBombardment(cow.location, target, isRageMode)
            "music_attack" -> musicAttackManager.startMusicAttack(cow.location, target, isRageMode)
            "summon_cows" -> summonManager.tryStartSummon(owner, cow, target, SummonManager.SummonType.COWS)
            "summon_parrots" -> summonManager.tryStartSummon(owner, cow, target, SummonManager.SummonType.PARROTS)
            "summon_rabbits" -> summonManager.tryStartSummon(owner, cow, target, SummonManager.SummonType.RABBITS)
        }

        effectManager.playShootEffects(cow.location, isRageMode)
        cow.health = (cow.health + ArrowConfig.HEAL_AMOUNT).coerceAtMost(cow.maxHealth)
    }


    // 处理药水和火球的伤害事件
    @EventHandler
    fun onProjectileHit(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        when {
            // 处理药水伤害
            damager is ThrownPotion && damager.hasMetadata("supercow_potion") -> {
                val shooter = damager.shooter as? Cow ?: return
                val ownerName = plugin.getActivePets().entries.find { it.value == shooter }?.key ?: return
                val isRageMode = rageMode[ownerName] ?: false

                event.damage = if (isRageMode) 4.0 else 2.0
            }

            // 处理火球伤害
            damager is SmallFireball && damager.hasMetadata("supercow_fireball") -> {
                val shooter = damager.shooter as? Cow ?: return
                val ownerName = plugin.getActivePets().entries.find { it.value == shooter }?.key ?: return
                val isRageMode = rageMode[ownerName] ?: false

                event.damage = if (isRageMode) 8.0 else 5.0

                // 设置燃烧时间
                if (event.entity is LivingEntity) {
                    (event.entity as LivingEntity).fireTicks =
                        if (isRageMode) ProjectileManager.Config.RAGE_FIREBALL_FIRE_TICKS
                        else ProjectileManager.Config.FIREBALL_FIRE_TICKS
                }
            }
        }
    }

    @EventHandler
    fun onFireworkExplode(event: EntityDamageByEntityEvent) {
        val firework = when (val damager = event.damager) {
            is Firework -> damager
            else -> return
        }

        if (!firework.hasMetadata("supercow_firework")) return

        val shooter = firework.shooter as? Cow ?: return
        val ownerName = plugin.getActivePets().entries.find { it.value == shooter }?.key ?: return
        val isRageMode = rageMode[ownerName] ?: false

        // 设置伤害值
        event.damage = if (isRageMode) ArrowConfig.RAGE_FIREWORK_DAMAGE else ArrowConfig.FIREWORK_DAMAGE
    }


    fun onPetRecall(playerName: String) {
        val cow = plugin.getActivePets()[playerName] ?: return
        clearCombatTarget(playerName)
        pathfindingManager.stopPathfinding(cow)
        summonManager.clearSummons(playerName)
    }

    fun onPetDeath(playerName: String) {
        val cow = plugin.getActivePets()[playerName] ?: return
        clearCombatTarget(playerName)
        pathfindingManager.stopPathfinding(cow)
        summonManager.clearSummons(playerName)

    }
    fun onDisable() {
        arrowTasks.values.forEach { it.cancel() }
        arrowTasks.clear()

        currentTargets.forEach { (playerName, targets) ->
            val cow = plugin.getActivePets()[playerName] ?: return@forEach
            targets.forEach { target ->
                stopGlowing(playerName, target)
            }
            pathfindingManager.stopPathfinding(cow)
        }

        currentTargets.clear()
        attackCooldowns.clear()
        glowingTargets.clear()
        rageMode.clear()
        summonManager.onDisable()
    }
}
