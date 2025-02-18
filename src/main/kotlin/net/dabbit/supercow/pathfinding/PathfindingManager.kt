package net.dabbit.supercow.pathfinding

import net.dabbit.supercow.SuperCow
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Cow
import org.bukkit.entity.Entity
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PathfindingManager(private val plugin: SuperCow) {

    private val pathfindingTasks = mutableMapOf<UUID, BukkitRunnable>()
    private val lastPositions = mutableMapOf<UUID, Location>()
    private val stuckTime = mutableMapOf<UUID, Int>()
    private val lastAttackTime = mutableMapOf<UUID, Long>()
    private val lastJumpTime = mutableMapOf<UUID, Long>() // 新增：记录上次跳跃时间

    companion object {
        private const val OPTIMAL_COMBAT_DISTANCE = 8.0
        private const val MAX_SPEED = 0.6
        private const val MIN_SPEED = 0.3
        private const val STRAFE_SPEED = 0.4
        private const val STUCK_THRESHOLD = 0.1
        private const val JUMP_POWER = 0.4
        private const val OBSTACLE_CHECK_DISTANCE = 2.0
        private const val DODGE_SPEED = 0.5
        private const val LINE_OF_SIGHT_CHECK_INTERVAL = 10 // ticks3
        private const val JUMP_COOLDOWN = 1500L // 跳跃冷却时间（毫秒）
        private const val RANDOM_JUMP_POWER = 0.5
    }

    fun startPathfinding(cow: Cow, target: Entity) {
        val cowId = cow.uniqueId

        pathfindingTasks[cowId]?.cancel()
        lastPositions[cowId] = cow.location
        stuckTime[cowId] = 0

        val task = object : BukkitRunnable() {
            private var strafeAngle = Random.nextDouble() * 360.0
            private var tickCount = 0
            private var lastLineOfSight = false
            private var alternatePathActive = false

            override fun run() {
                tickCount++

                if (!cow.isValid || !target.isValid) {
                    cancel()
                    cleanup(cowId)
                    return
                }

                val currentLoc = cow.location
                val targetLoc = target.location
                val distance = currentLoc.distance(targetLoc)

                // 检查视线
                if (tickCount % LINE_OF_SIGHT_CHECK_INTERVAL == 0) {
                    lastLineOfSight = hasLineOfSight(currentLoc, targetLoc)
                    if (!lastLineOfSight) {
                        alternatePathActive = true
                    }
                }

                // 检测卡住状态
                checkStuckStatus(cow, currentLoc)

                // 计算移动向量
                val moveVector = if (alternatePathActive) {
                    calculateAlternativePath(cow, target, distance)
                } else {
                    calculateMoveVector(currentLoc, targetLoc, distance, strafeAngle)
                }

                // 处理障碍物
                val finalVector = handleObstacles(cow, moveVector)

                // 更新移动
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    cow.velocity = finalVector
                })

                // 更新横移角度
                updateStrafeAngle()
            }

            private fun updateStrafeAngle() {
                // 动态调整横移速度和方向
                val angleChange = if (Random.nextDouble() < 0.1) {
                    Random.nextDouble(-45.0, 45.0)
                } else {
                    10.0
                }
                strafeAngle = (strafeAngle + angleChange) % 360.0
            }
        }

        task.runTaskTimerAsynchronously(plugin, 0L, 1L)
        pathfindingTasks[cowId] = task
    }

    private fun hasLineOfSight(from: Location, to: Location): Boolean {
        val direction = to.clone().subtract(from).toVector()
        val distance = from.distance(to)
        val step = 0.2

        for (i in 0 until (distance / step).toInt()) {
            val checkLoc = from.clone().add(direction.clone().multiply(i * step))
            if (checkLoc.block.type.isSolid) {
                return false
            }
        }
        return true
    }

    private fun checkStuckStatus(cow: Cow, currentLoc: Location) {
        val lastPos = lastPositions[cow.uniqueId]
        if (lastPos != null && lastPos.distance(currentLoc) < STUCK_THRESHOLD) {
            stuckTime[cow.uniqueId] = (stuckTime[cow.uniqueId] ?: 0) + 1
        } else {
            stuckTime[cow.uniqueId] = 0
        }
        lastPositions[cow.uniqueId] = currentLoc
    }

    private fun calculateAlternativePath(cow: Cow, target: Entity, distance: Double): Vector {
        val currentLoc = cow.location
        val targetLoc = target.location

        // 寻找可通行的路径点
        val possiblePoints = mutableListOf<Location>()
        for (angle in 0..360 step 45) {
            val rad = Math.toRadians(angle.toDouble())
            val checkPoint = currentLoc.clone().add(
                cos(rad) * OPTIMAL_COMBAT_DISTANCE,
                0.0,
                sin(rad) * OPTIMAL_COMBAT_DISTANCE
            )

            if (hasLineOfSight(checkPoint, targetLoc) && isLocationSafe(checkPoint)) {
                possiblePoints.add(checkPoint)
            }
        }

        // 选择最佳路径点
        return if (possiblePoints.isNotEmpty()) {
            possiblePoints.minByOrNull {
                it.distance(targetLoc)
            }?.subtract(currentLoc)?.toVector()?.normalize()?.multiply(MAX_SPEED)
                ?: calculateMoveVector(currentLoc, targetLoc, distance, 0.0)
        } else {
            calculateMoveVector(currentLoc, targetLoc, distance, 0.0)
        }
    }

    private fun isLocationSafe(location: Location): Boolean {
        // 检查位置是否安全（没有岩浆、虚空等）
        val block = location.block
        val above = block.getRelative(0, 1, 0)
        val below = block.getRelative(0, -1, 0)

        return !block.type.isSolid &&
                !above.type.isSolid &&
                below.type.isSolid &&
                !below.isLiquid
    }

    private fun handleObstacles(cow: Cow, moveVector: Vector): Vector {
        val loc = cow.location
        val adjustedVector = moveVector.clone()

        // 检查前方障碍物
        val frontBlock = loc.clone().add(moveVector.clone().normalize().multiply(OBSTACLE_CHECK_DISTANCE)).block
        if (frontBlock.type.isSolid) {
            // 寻找替代路径
            val leftVector = rotateVectorAroundY(moveVector.clone(), Math.PI / 2)
            val leftClear = !loc.clone().add(leftVector).block.type.isSolid
            val rightVector = rotateVectorAroundY(moveVector.clone(), -Math.PI / 2)
            val rightClear = !loc.clone().add(rightVector).block.type.isSolid

            when {
                leftClear -> adjustedVector.setX(leftVector.x).setZ(leftVector.z)
                rightClear -> adjustedVector.setX(rightVector.x).setZ(rightVector.z)
                else -> {
                    // 如果两边都被堵住，尝试跳跃或后退
                    if (!loc.clone().add(0.0, 2.0, 0.0).block.type.isSolid) {
                        adjustedVector.setY(JUMP_POWER)
                    } else {
                        adjustedVector.multiply(-1)
                    }
                }
            }
        }

        // 检查头顶障碍物
        if (loc.clone().add(0.0, 2.0, 0.0).block.type.isSolid) {
            adjustedVector.setY(-0.1)
        }

        return adjustedVector
    }

    private fun calculateMoveVector(
        currentLoc: Location,
        targetLoc: Location,
        distance: Double,
        strafeAngle: Double
    ): Vector {
        // 安全获取方向向量
        val rawDirection = targetLoc.clone().subtract(currentLoc).toVector()
        val direction = when {
            rawDirection.lengthSquared() < 0.0001 -> Vector.getRandom().subtract(Vector(0.5, 0.5, 0.5)).normalize()
            else -> try {
                rawDirection.normalize()
            } catch (e: IllegalArgumentException) {
                Vector.getRandom().normalize()
            }
        }

        // 安全计算速度
        val speed = when {
            distance > OPTIMAL_COMBAT_DISTANCE * 1.5 -> MAX_SPEED
            distance < OPTIMAL_COMBAT_DISTANCE * 0.5 -> -MIN_SPEED
            else -> (distance - OPTIMAL_COMBAT_DISTANCE).coerceIn(-MIN_SPEED, MAX_SPEED) * 0.1
        }.finiteValue()

        // 生成安全横向向量
        val strafeRad = Math.toRadians(strafeAngle)
        val strafeVector = try {
            Vector(
                cos(strafeRad).finiteValue() * STRAFE_SPEED,
                0.0,
                sin(strafeRad).finiteValue() * STRAFE_SPEED
            )
        } catch (e: Exception) {
            Vector()
        }

        // 合并向量并验证
        val moveVector = try {
            direction.multiply(speed).add(strafeVector).apply {
                x = x.finiteValue()
                y = y.finiteValue()
                z = z.finiteValue()
            }
        } catch (e: Exception) {
            Vector().also {
                plugin.logger.warning("生成无效移动向量: ${e.message}")
            }
        }

        // 随机跳跃逻辑
//        val cowId = cow.uniqueId
        val currentTime = System.currentTimeMillis()
        val lastJump =0L

        if (currentTime - lastJump > JUMP_COOLDOWN && Random.nextDouble() < 0.05) { // 5%的概率跳跃
            moveVector.y = RANDOM_JUMP_POWER
//            lastJumpTime[cowId] = currentTime
        }

        return moveVector
    }

    // 数值安全扩展函数
    private fun Double.finiteValue(): Double {
        return when {
            this.isNaN() -> 0.0
            this.isInfinite() -> if (this > 0) Double.MAX_VALUE else Double.MIN_VALUE
            else -> this.coerceIn(-MAX_SPEED, MAX_SPEED)
        }
    }

    private fun rotateVectorAroundY(vector: Vector, angle: Double): Vector {
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val newX = vector.x * cosAngle - vector.z * sinAngle
        val newZ = vector.x * sinAngle + vector.z * cosAngle
        return Vector(newX, vector.y, newZ)
    }

    private fun cleanup(cowId: UUID) {
        pathfindingTasks.remove(cowId)
        lastPositions.remove(cowId)
        stuckTime.remove(cowId)
        lastAttackTime.remove(cowId)
        lastJumpTime.remove(cowId) // 清理跳跃时间记录
    }

    fun stopPathfinding(cow: Cow) {
        cleanup(cow.uniqueId)
        pathfindingTasks[cow.uniqueId]?.cancel()
    }
}