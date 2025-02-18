package net.dabbit.supercow.combat

import net.dabbit.supercow.SuperCow
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Firework
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector

class FireworkManager(private val plugin: SuperCow) {

    object Config {
        const val MAX_LIFETIME_TICKS = 100  // 减少最大存活时间
        const val TRACKING_SPEED = 0.15     // 减小转向速度使运动更平滑
        const val DETONATION_DISTANCE = 2.0
        const val BASE_VELOCITY = 1.2       // 调整初始速度
        const val RAGE_VELOCITY = 1.5
        const val MIN_SHOOT_HEIGHT = 1.5    // 最小发射高度

        // 添加新的配置
        const val INITIAL_VERTICAL_BOOST = 0.5  // 初始向上推力
        const val MAX_TURN_ANGLE = 15.0        // 最大转向角度（度）
    }

    fun shootFirework(shootLocation: Location, target: Entity, isRageMode: Boolean) {
        val spawnLocation = shootLocation.clone().add(0.0, Config.MIN_SHOOT_HEIGHT, 0.0)

        val firework = spawnLocation.world?.spawn(
            spawnLocation,
            Firework::class.java
        ) ?: return

        setupFireworkMeta(firework, isRageMode)

        val targetPos = target.location.clone().add(0.0, 1.0, 0.0)
        val direction = targetPos.subtract(spawnLocation).toVector().normalize()

        // 修正初始速度设置
        val initialVelocity = direction.clone().multiply(
            if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY
        )
        initialVelocity.setY(initialVelocity.y + Config.INITIAL_VERTICAL_BOOST)

        firework.velocity = initialVelocity
        firework.setMetadata("supercow_firework", plugin.fixedMetadataValue())

        firework.fireworkMeta = firework.fireworkMeta.apply {
            power = 2
        }

        playLaunchEffects(spawnLocation, isRageMode)
        startFireworkTracking(firework, target, isRageMode)
    }

    private fun setupFireworkMeta(firework: Firework, isRageMode: Boolean) {
        val meta = firework.fireworkMeta
        val effect = FireworkEffect.builder().apply {
            val colors = generateRandomColors(if (isRageMode) 5 else 3)
            val fadeColors = generateRandomColors(if (isRageMode) 3 else 2)

            withColor(*colors.toTypedArray())
            withFade(*fadeColors.toTypedArray())

            with(if (isRageMode) {
                listOf(
                    FireworkEffect.Type.BALL_LARGE,
                    FireworkEffect.Type.BURST,
                    FireworkEffect.Type.STAR
                ).random()
            } else {
                listOf(
                    FireworkEffect.Type.BALL,
                    FireworkEffect.Type.STAR
                ).random()
            })

            trail(true)
            flicker(isRageMode)
        }.build()

        meta.addEffect(effect)
        meta.power = 2  // 增加动力
        firework.fireworkMeta = meta
    }

    private fun playLaunchEffects(location: Location, isRageMode: Boolean) {
//        location.world?.apply {
//            playSound(
//                location,
//                Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
//                1.0f,
//                if (isRageMode) 0.5f else 1.0f
//            )
//            spawnParticle(
//                if (isRageMode) Particle.FLAME else Particle.FIREWORKS_SPARK,
//                location.add(0.0, 1.0, 0.0),
//                10,
//                0.2, 0.2, 0.2,
//                0.05
//            )
//        }
    }

    private fun startFireworkTracking(firework: Firework, target: Entity, isRageMode: Boolean) {
        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                if (!firework.isValid || ticks >= Config.MAX_LIFETIME_TICKS) {
                    if (firework.isValid) {
                        detonateFirework(firework, isRageMode)
                    }
                    cancel()
                    return
                }

                if (!target.isValid || target.isDead) {
                    detonateFirework(firework, isRageMode)
                    cancel()
                    return
                }

                val distance = firework.location.distance(target.location)
                if (distance <= Config.DETONATION_DISTANCE) {
                    playHitEffects(target.location, isRageMode)
                    detonateFirework(firework, isRageMode)
                    cancel()
                    return
                }

                updateFireworkTrajectory(firework, target, isRageMode)
                ticks++
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun playHitEffects(location: Location, isRageMode: Boolean) {
//        location.world?.apply {
//            playSound(
//                location,
//                Sound.ENTITY_FIREWORK_ROCKET_BLAST,
//                1.0f,
//                if (isRageMode) 0.5f else 1.0f
//            )
//            spawnParticle(
//                if (isRageMode) Particle.EXPLOSION_LARGE else Particle.EXPLOSION_NORMAL,
//                location,
//                5,
//                0.2, 0.2, 0.2,
//                0.05
//            )
//        }
    }

    private fun updateFireworkTrajectory(firework: Firework, target: Entity, isRageMode: Boolean) {
        val currentVel = firework.velocity
        val targetDir = target.location.clone().add(0.0, 1.0, 0.0)
            .subtract(firework.location)
            .toVector()
            .normalize()
            .multiply(if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY)

        // 限制转向角度
        val angle = currentVel.angle(targetDir)
        val maxAngle = Math.toRadians(Config.MAX_TURN_ANGLE)

        val newDir = if (angle > maxAngle) {
            // 如果转向角度过大，进行限制
            val limitedDir = limitRotation(currentVel.clone(), targetDir, maxAngle)
            limitedDir.multiply(currentVel.length())
        } else {
            targetDir
        }

        // 平滑转向
        val smoothedVel = currentVel.clone().multiply(1 - Config.TRACKING_SPEED)
            .add(newDir.clone().multiply(Config.TRACKING_SPEED))

        firework.velocity = smoothedVel

        // 轨迹特效
        firework.world.spawnParticle(
            if (isRageMode) Particle.DRAGON_BREATH else Particle.FIREWORKS_SPARK,
            firework.location,
            3,
            0.1, 0.1, 0.1,
            0.01
        )
    }
    private fun limitRotation(current: Vector, target: Vector, maxAngle: Double): Vector {
        val currentNorm = current.clone().normalize()
        val targetNorm = target.clone().normalize()

        val angle = current.angle(target)
        if (angle <= maxAngle) return targetNorm

        // 计算旋转轴
        val axis = currentNorm.getCrossProduct(targetNorm)
        if (axis.lengthSquared() == 0.0) return currentNorm
        axis.normalize()

        // 使用四元数进行旋转
        val sin = Math.sin(maxAngle / 2.0)
        val cos = Math.cos(maxAngle / 2.0)

        val x = axis.x * sin
        val y = axis.y * sin
        val z = axis.z * sin
        val w = cos

        // 应用四元数旋转
        val result = Vector(
            (1 - 2 * (y * y + z * z)) * currentNorm.x + (2 * (x * y - w * z)) * currentNorm.y + (2 * (x * z + w * y)) * currentNorm.z,
            (2 * (x * y + w * z)) * currentNorm.x + (1 - 2 * (x * x + z * z)) * currentNorm.y + (2 * (y * z - w * x)) * currentNorm.z,
            (2 * (x * z - w * y)) * currentNorm.x + (2 * (y * z + w * x)) * currentNorm.y + (1 - 2 * (x * x + y * y)) * currentNorm.z
        )

        return result.normalize()
    }
    private fun detonateFirework(firework: Firework, isRageMode: Boolean) {
        playHitEffects(firework.location, isRageMode)
        firework.detonate()
    }

    private fun generateRandomColors(count: Int): List<Color> {
        val baseColors = listOf(
            Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW,
            Color.PURPLE, Color.AQUA, Color.FUCHSIA, Color.ORANGE,
            Color.LIME, Color.MAROON, Color.NAVY, Color.OLIVE, Color.TEAL
        )

        val colors = ArrayList<Color>()
        repeat(count) {
            val baseColor = baseColors.random()
            colors.add(Color.fromRGB(
                (baseColor.red + (-20..20).random()).coerceIn(0..255),
                (baseColor.green + (-20..20).random()).coerceIn(0..255),
                (baseColor.blue + (-20..20).random()).coerceIn(0..255)
            ))
        }
        return colors
    }
}