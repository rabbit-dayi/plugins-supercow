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



class PathfindingManager(private val plugin: SuperCow) {

    private val pathfindingTasks = mutableMapOf<UUID, BukkitRunnable>()
    private val lastPositions = mutableMapOf<UUID, Location>()
    private val stuckTime = mutableMapOf<UUID, Int>()

    companion object {
        private const val OPTIMAL_COMBAT_DISTANCE = 8.0 // 最佳战斗距离
        private const val MAX_SPEED = 0.6 // 最大速度
        private const val MIN_SPEED = 0.3 // 最小速度
        private const val STRAFE_SPEED = 0.3 // 横向移动速度
        private const val STUCK_THRESHOLD = 0.1 // 卡住的阈值
        private const val JUMP_POWER = 0.3 // 跳跃力度
    }

    fun startPathfinding(cow: Cow, target: Entity) {
        val cowId = cow.uniqueId

        pathfindingTasks[cowId]?.cancel()
        lastPositions[cowId] = cow.location
        stuckTime[cowId] = 0

        val task = object : BukkitRunnable() {
            private var strafeAngle = 0.0

            override fun run() {
                if (!cow.isValid || !target.isValid) {
                    cancel()
                    cleanup(cowId)
                    return
                }

                val currentLoc = cow.location
                val targetLoc = target.location
                val distance = currentLoc.distance(targetLoc)

                // 检测是否卡住
                val lastPos = lastPositions[cowId]
                if (lastPos != null && lastPos.distance(currentLoc) < STUCK_THRESHOLD) {
                    stuckTime[cowId] = (stuckTime[cowId] ?: 0) + 1
                } else {
                    stuckTime[cowId] = 0
                }
                lastPositions[cowId] = currentLoc

                // 计算移动向量
                val moveVector = calculateMoveVector(
                    currentLoc,
                    targetLoc,
                    distance,
                    strafeAngle
                )

                // 如果卡住了，尝试跳跃或改变方向
                if ((stuckTime[cowId] ?: 0) > 5) {
                    handleStuckSituation(cow, moveVector)
                    strafeAngle += 45.0 // 改变横移角度
                }

                // 更新移动
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    cow.velocity = moveVector
                })

                // 更新横移角度
                strafeAngle += 10.0
                if (strafeAngle >= 360.0) strafeAngle = 0.0
            }
        }

        task.runTaskTimerAsynchronously(plugin, 0L, 1L) // 提高更新频率到2tick
        pathfindingTasks[cowId] = task
    }

    private fun calculateMoveVector(
        currentLoc: Location,
        targetLoc: Location,
        distance: Double,
        strafeAngle: Double
    ): Vector {
        // 基础方向向量
        val direction = targetLoc.subtract(currentLoc).toVector().normalize()

        // 根据距离动态调整速度
        val speed = when {
            distance > OPTIMAL_COMBAT_DISTANCE * 1.5 -> MAX_SPEED // 距离过远，全速追击
            distance < OPTIMAL_COMBAT_DISTANCE * 0.5 -> -MIN_SPEED // 距离过近，后退
            else -> calculateOptimalSpeed(distance) // 在最佳范围内动态调整速度
        }

        // 计算横向移动
        val strafeRad = Math.toRadians(strafeAngle)
        val strafeX = cos(strafeRad) * STRAFE_SPEED
        val strafeZ = sin(strafeRad) * STRAFE_SPEED

        // 合并移动向量
        return direction.multiply(speed).add(Vector(strafeX, 0.0, strafeZ))
    }

    private fun calculateOptimalSpeed(distance: Double): Double {
        // 根据与最佳距离的差值动态调整速度
        val distanceDiff = distance - OPTIMAL_COMBAT_DISTANCE
        return distanceDiff * 0.1.coerceIn(-MIN_SPEED, MAX_SPEED)
    }

    private fun handleStuckSituation(cow: Cow, moveVector: Vector) {
        // 检测头顶方块
        val headBlock = cow.location.clone().add(0.0, 1.0, 0.0).block

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (headBlock.type.isSolid) {
                // 如果头顶有方块，尝试向侧面移动
                moveVector.add(Vector(
                    (Math.random() - 0.5) * MAX_SPEED,
                    0.0,
                    (Math.random() - 0.5) * MAX_SPEED
                ))
            } else {
                // 否则尝试跳跃
                moveVector.setY(JUMP_POWER)
            }

            // 应用新的移动向量
            cow.velocity = moveVector
        })

        // 重置卡住计时器
        stuckTime[cow.uniqueId] = 0
    }

    private fun cleanup(cowId: UUID) {
        pathfindingTasks.remove(cowId)
        lastPositions.remove(cowId)
        stuckTime.remove(cowId)
    }

    fun stopPathfinding(cow: Cow) {
        val cowId = cow.uniqueId
        pathfindingTasks[cowId]?.cancel()
        cleanup(cowId)
    }
}