package net.dabbit.supercow.combat

import net.dabbit.supercow.pathfinding.PathfindingManager
import net.dabbit.supercow.SuperCow
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.scheduler.BukkitRunnable

class CombatManager(
    private val plugin: SuperCow,
    private val pathfindingManager: PathfindingManager,
    private val effectManager: ArrowEffectManager,
    private val targetValidator: TargetValidator,
    private val trajectoryCalculator: ArrowTrajectoryCalculator
) : Listener {
    private val currentTargets = mutableMapOf<String, MutableSet<Entity>>()
    private val attackCooldowns = mutableMapOf<String, Long>()
    private val arrowTasks = mutableMapOf<String, BukkitRunnable>()
    private val glowingTargets = mutableMapOf<String, MutableSet<Entity>>()
    private val rageMode = mutableMapOf<String, Boolean>()

    companion object {
        private const val ATTACK_COOLDOWN = 1000L
        private const val BASE_ARROW_SPEED = 1.5
        private const val RAGE_ARROW_SPEED = 2.5
        private const val SHOOT_RANGE = 16.0
        private const val BASE_DAMAGE = 5.0
        private const val RAGE_DAMAGE = 8.0
        private const val MAX_TARGETS = 3
        private const val RAGE_MODE_THRESHOLD = 0.3 // 30%血量以下进入狂暴
    }


    @EventHandler
    fun onPlayerAttackEntity(event: EntityDamageByEntityEvent) {
        val player = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> return
        } ?: return

        val cow = plugin.getActivePets()[player.name] ?: return
        val target = event.entity
        if (!isValidAttackTarget(target)) return

        // 检查周围实体并添加多个目标
        addNearbyTargets(player.name, target, cow)
    }

    @EventHandler
    fun onPlayerGetDamaged(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        val cow = plugin.getActivePets()[player.name] ?: return

        val attacker = when (val damager = event.damager) {
            is Projectile -> damager.shooter as? Entity ?: return
            else -> damager
        }

        if (!isValidAttackTarget(attacker)) return
        addNearbyTargets(player.name, attacker, cow)
    }

    @EventHandler
    fun onCowGetDamaged(event: EntityDamageByEntityEvent) {
        val cow = event.entity as? Cow ?: return

        // 检查这头牛是否是超级牛宠物
        val ownerName = plugin.getActivePets().entries.find { it.value == cow }?.key ?: return

        val attacker = when (val damager = event.damager) {
            is Projectile -> damager.shooter as? Entity ?: return
            else -> damager
        }

        if (!isValidAttackTarget(attacker)) return
        addNearbyTargets(ownerName, attacker, cow)
    }

    private fun addNearbyTargets(ownerName: String, primaryTarget: Entity, cow: Cow) {
        val targets = mutableSetOf<Entity>()
        targets.add(primaryTarget)

        // 获取周围的其他目标
        cow.location.world?.getNearbyEntities(cow.location, SHOOT_RANGE, SHOOT_RANGE, SHOOT_RANGE)
            ?.filter { isValidAttackTarget(it) && it != primaryTarget }
            ?.take(MAX_TARGETS - 1)
            ?.forEach { targets.add(it) }

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
        if (target !is LivingEntity) return 20L

        val maxHealth = target.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
        val currentHealth = target.health
        val healthPercentage = currentHealth / maxHealth

        // 血量越低攻击速度越快
        return (20L * healthPercentage).toLong().coerceAtLeast(5L)
    }
    private fun faceCowToTarget(cow: Cow, targetLocation: Location) {
        val direction = targetLocation.clone().subtract(cow.location).toVector()
        cow.location.direction = direction
    }

    private fun stopShooting(ownerName: String) {
        arrowTasks[ownerName]?.cancel()
        arrowTasks.remove(ownerName)
    }

    // CombatManager.kt 中的 shootArrow 函数修改
    private fun shootArrow(cow: Cow, target: Entity, ownerName: String) {
        val currentTime = System.currentTimeMillis()
        val lastAttackTime = attackCooldowns[cow.uniqueId.toString()] ?: 0L
        val owner = plugin.server.getPlayer(ownerName)

        if (!targetValidator.isValidTarget(target, owner)) return

        val isRageMode = rageMode[ownerName] ?: false
        val actualCooldown = if (isRageMode) ATTACK_COOLDOWN / 2 else ATTACK_COOLDOWN

        if (currentTime - lastAttackTime < actualCooldown) return

        attackCooldowns[cow.uniqueId.toString()] = currentTime

        val shootPos = cow.location.clone()
        val velocity = trajectoryCalculator.calculateArrowVelocity(
            shootPos,
            target.location,
            if (isRageMode) ArrowConfig.RAGE_ARROW_SPEED else ArrowConfig.BASE_ARROW_SPEED
        )

        val arrow = cow.world.spawnArrow(
            shootPos.add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0),
            velocity,
            if (isRageMode) ArrowConfig.RAGE_ARROW_SPEED.toFloat() else ArrowConfig.BASE_ARROW_SPEED.toFloat(),
            0f
        )

        arrow.shooter = cow
        arrow.setMetadata("supercow_arrow", plugin.fixedMetadataValue())
        arrow.isCritical = true
        arrow.damage = if (isRageMode) ArrowConfig.RAGE_DAMAGE else ArrowConfig.BASE_DAMAGE

        effectManager.playShootEffects(cow.location, isRageMode)

        // 生命恢复效果
        cow.health = (cow.health + ArrowConfig.HEAL_AMOUNT).coerceAtMost(cow.maxHealth)
    }

//    private fun shootArrow(cow: Cow, target: Entity, ownerName: String) {
//        val currentTime = System.currentTimeMillis()
//        val lastAttackTime = attackCooldowns[cow.uniqueId.toString()] ?: 0L
//
//        val isRageMode = rageMode[ownerName] ?: false
//        val actualCooldown = if (isRageMode) ATTACK_COOLDOWN / 2 else ATTACK_COOLDOWN
//
//        if (currentTime - lastAttackTime < actualCooldown) return
//
//        attackCooldowns[cow.uniqueId.toString()] = currentTime
//
//        val shootPos = cow.location.clone().add(0.0, 1.5, 0.0)
//        val targetPos = target.location.clone().add(0.0, 0.5, 0.0)
//
//        if (isRageMode) {
//            // 狂暴模式下跳跃射击
//            cow.velocity = Vector(0.0, 0.5, 0.0)
//        }
//
//        val direction = targetPos.clone().subtract(shootPos).toVector().normalize()
//
//        val arrow = cow.world.spawnArrow(
//            shootPos,
//            direction,
//            if (isRageMode) RAGE_ARROW_SPEED.toFloat() else BASE_ARROW_SPEED.toFloat(),
//            0.5f
//        )
//
//        arrow.shooter = cow
//        arrow.setMetadata("supercow_arrow", plugin.fixedMetadataValue())
//        arrow.isCritical = true
//        arrow.damage = if (isRageMode) RAGE_DAMAGE else BASE_DAMAGE
//
//        playShootEffects(cow.location, isRageMode)
//    }

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
                        (entity is Tameable && entity.isTamed) ||
                        entity is Cow)
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

    fun onPetRecall(playerName: String) {
        val cow = plugin.getActivePets()[playerName] ?: return
        clearCombatTarget(playerName)
        pathfindingManager.stopPathfinding(cow)
    }

    fun onPetDeath(playerName: String) {
        val cow = plugin.getActivePets()[playerName] ?: return
        clearCombatTarget(playerName)
        pathfindingManager.stopPathfinding(cow)
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
    }
}
