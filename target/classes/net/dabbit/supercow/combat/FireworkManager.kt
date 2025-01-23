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

class FireworkManager(private val plugin: SuperCow) {

    object Config {
        const val MAX_LIFETIME_TICKS = 100
        const val TRACKING_SPEED = 0.2
        const val DETONATION_DISTANCE = 2.0
        const val BASE_VELOCITY = 8.0
        const val RAGE_VELOCITY = 8.5
    }

    fun shootFirework(shootLocation: Location, target: Entity, isRageMode: Boolean) {
        // 生成烟花
        val firework = shootLocation.world?.spawn(
            shootLocation.clone().add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0),
            Firework::class.java
        ) ?: return

        // 设置烟花元数据
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
        meta.power = if (isRageMode) ArrowConfig.RAGE_FIREWORK_POWER else ArrowConfig.FIREWORK_POWER
        firework.fireworkMeta = meta

        // 设置初始速度和方向
        val initialDirection = target.location.clone()
            .subtract(firework.location)
            .toVector()
            .normalize()
            .multiply(if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY)

        firework.velocity = initialDirection

        // 添加自定义标签
        firework.setMetadata("supercow_firework", plugin.fixedMetadataValue())

        // 播放发射效果
        playLaunchEffects(shootLocation, isRageMode)

        // 烟花追踪和爆炸控制
        startFireworkTracking(firework, target, isRageMode)
    }

    private fun playLaunchEffects(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            playSound(
                location,
                Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
                1.0f,
                if (isRageMode) 0.5f else 1.0f
            )
            spawnParticle(
                if (isRageMode) Particle.FLAME else Particle.FIREWORKS_SPARK,
                location.add(0.0, 1.0, 0.0),
                10,
                0.2, 0.2, 0.2,
                0.05
            )
        }
    }

    private fun startFireworkTracking(firework: Firework, target: Entity, isRageMode: Boolean) {
        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                ticks++

                if (!firework.isValid || ticks >= Config.MAX_LIFETIME_TICKS) {
                    if (firework.isValid) firework.detonate()
                    cancel()
                    return
                }

                if (!target.isValid || target.isDead) {
                    firework.detonate()
                    cancel()
                    return
                }

                val distance = firework.location.distance(target.location)
                if (distance <= Config.DETONATION_DISTANCE) {
                    playHitEffects(target.location, isRageMode)
                    firework.detonate()
                    cancel()
                    return
                }

                updateFireworkTrajectory(firework, target, isRageMode)
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun playHitEffects(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            playSound(
                location,
                Sound.ENTITY_FIREWORK_ROCKET_BLAST,
                1.0f,
                if (isRageMode) 0.5f else 1.0f
            )
            spawnParticle(
                if (isRageMode) Particle.EXPLOSION_LARGE else Particle.EXPLOSION_NORMAL,
                location,
                5,
                0.2, 0.2, 0.2,
                0.05
            )
        }
    }

    private fun updateFireworkTrajectory(firework: Firework, target: Entity, isRageMode: Boolean) {
        val newDirection = target.location.clone()
            .subtract(firework.location)
            .toVector()
            .normalize()
            .multiply(if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY)

        val currentVel = firework.velocity
        val smoothedVel = currentVel.add(
            newDirection.subtract(currentVel).multiply(Config.TRACKING_SPEED)
        )

        firework.velocity = smoothedVel

        firework.world.spawnParticle(
            if (isRageMode) Particle.DRAGON_BREATH else Particle.FIREWORKS_SPARK,
            firework.location,
            3,
            0.1, 0.1, 0.1,
            0.01
        )
    }

    private fun generateRandomColors(count: Int): List<Color> {
        val baseColors = listOf(
            Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW,
            Color.PURPLE, Color.AQUA, Color.FUCHSIA, Color.ORANGE,
            Color.LIME, Color.MAROON, Color.NAVY, Color.OLIVE, Color.TEAL
        )

        return buildList {
            repeat(count) {
                val baseColor = baseColors.random()
                add(Color.fromRGB(
                    (baseColor.red + (-20..20).random()).coerceIn(0..255),
                    (baseColor.green + (-20..20).random()).coerceIn(0..255),
                    (baseColor.blue + (-20..20).random()).coerceIn(0..255)
                ))
            }
        }
    }
}