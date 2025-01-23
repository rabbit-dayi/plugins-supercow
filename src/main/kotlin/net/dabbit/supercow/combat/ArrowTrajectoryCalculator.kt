package net.dabbit.supercow.combat

// ArrowTrajectoryCalculator.kt

import net.dabbit.supercow.SuperCow
import org.bukkit.Location
import org.bukkit.util.Vector
import org.bukkit.Particle
import org.bukkit.Sound

class ArrowTrajectoryCalculator {

    fun calculateArrowVelocity(start: Location, target: Location, speed: Double): Vector {
        val from = start.clone().add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0)
        val to = target.clone().add(0.0, ArrowConfig.TARGET_HEIGHT, 0.0)

        val direction = to.clone().subtract(from).toVector()
        val distance = direction.length()

        // 计算抛物线
        val gravity = ArrowConfig.GRAVITY
        val time = distance / speed
        val velocityY = gravity * time / 2

        direction.normalize()
        direction.multiply(speed)
        direction.y += velocityY

        // 添加一些随机散布
        direction.add(Vector(
            Math.random() * ArrowConfig.SPREAD - ArrowConfig.SPREAD / 2,
            Math.random() * ArrowConfig.SPREAD - ArrowConfig.SPREAD / 2,
            Math.random() * ArrowConfig.SPREAD - ArrowConfig.SPREAD / 2
        ))

        return direction
    }
}