package net.dabbit.supercow.combat

import net.dabbit.supercow.SuperCow
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Cow
import org.bukkit.entity.Entity
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.random.Random
class ExplodingCowManager(private val plugin: SuperCow) {

    object Config {
        // 通用配置
        const val MAX_LIFETIME_TICKS = 5
        const val TRACKING_SPEED = 0.2
        const val BASE_VELOCITY = 1.5
        const val RAGE_VELOCITY = 2.2

        // 爆炸配置
        const val BASE_EXPLOSION_POWER = 2.0f
        const val RAGE_EXPLOSION_POWER = 3.5f
        // 移除火焰相关配置
        // const val BASE_FIRE_TICKS = 60
        // const val RAGE_FIRE_TICKS = 100

        // 音乐配置
        val FLYING_SOUNDS = listOf(
            Sound.ENTITY_COW_AMBIENT,
            Sound.ENTITY_COW_HURT,
            Sound.ENTITY_GHAST_SHOOT
        )
        const val SOUND_INTERVAL = 10L // 音效间隔(ticks)
    }

    fun launchExplodingCow(shootLocation: Location, target: Entity, isRageMode: Boolean) {
        val cow = shootLocation.world?.spawn(
            shootLocation.clone().add(0.0, 1.0, 0.0),
            Cow::class.java
        ) ?: return

        // 设置牛的属性
        cow.isGlowing = true
        cow.setAI(false)
        cow.isInvulnerable = true
        // 移除火焰设置
        // cow.fireTicks = if (isRageMode) Config.RAGE_FIRE_TICKS else Config.BASE_FIRE_TICKS

        // 添加自定义标签
        cow.setMetadata("supercow_explosive", plugin.fixedMetadataValue())

        // 设置初始速度和方向
        val initialDirection = calculateInitialDirection(shootLocation, target.location)
        cow.velocity = initialDirection.multiply(if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY)

        // 播放发射效果
        playLaunchEffects(shootLocation, isRageMode)

        // 开始追踪控制
        startCowTracking(cow, target, isRageMode)
    }

    private fun calculateInitialDirection(from: Location, to: Location): Vector {
        val distance = from.distance(to)

        // 根据距离调整发射角度和速度
        val heightGain = when {
            distance < 5 -> 0.2
            distance < 10 -> 0.3
            distance < 15 -> 0.4
            else -> 0.5
        }

        // 计算基础方向
        val direction = to.clone().subtract(from).toVector()

        // 水平距离
        val horizontalDistance = Math.sqrt(direction.x * direction.x + direction.z * direction.z)

        // 调整Y轴分量来创建抛物线
        direction.y = horizontalDistance * heightGain

        // 添加随机偏移
        val spread = 0.1 * (distance / 10.0) // 距离越远spread越大
        direction.apply {
            x += Random.nextDouble(-spread, spread)
            y += Random.nextDouble(-spread, spread)
            z += Random.nextDouble(-spread, spread)
        }

        return direction.normalize()
    }

    private fun playLaunchEffects(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            // 播放发射音效
            playSound(
                location,
                Sound.ENTITY_COW_AMBIENT,
                1.0f,
                if (isRageMode) 0.5f else 1.0f
            )
            playSound(
                location,
                Sound.ENTITY_GENERIC_EXPLODE,
                0.5f,
                1.5f
            )

            // 生成发射粒子，改用不含火焰的粒子
            spawnParticle(
                Particle.SMOKE_NORMAL,
                location.add(0.0, 1.0, 0.0),
                30,
                0.3, 0.3, 0.3,
                0.05
            )
        }
    }

    private fun startCowTracking(cow: Cow, target: Entity, isRageMode: Boolean) {
        object : BukkitRunnable() {
            private var ticks = 0
            private var soundTicks = 0

            override fun run() {
                ticks++
                soundTicks++

                if (!cow.isValid || ticks >= Config.MAX_LIFETIME_TICKS) {
                    if (cow.isValid) {
                        createCowExplosion(cow.location, isRageMode)
                        cow.remove()
                    }
                    cancel()
                    return
                }

                if (!target.isValid || target.isDead) {
                    createCowExplosion(cow.location, isRageMode)
                    cow.remove()
                    cancel()
                    return
                }

                // 播放飞行音效
                if (soundTicks >= Config.SOUND_INTERVAL) {
                    cow.world.playSound(
                        cow.location,
                        Config.FLYING_SOUNDS.random(),
                        1.0f,
                        if (isRageMode) 0.5f else 1.0f
                    )
                    soundTicks = 0
                }

                // 更新牛的朝向
                val targetDir = target.location.clone()
                    .subtract(cow.location)
                    .toVector()
                    .normalize()
                    .multiply(if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY)

                // 平滑转向
                val currentVel = cow.velocity
                val newVel = currentVel.add(
                    targetDir.subtract(currentVel).multiply(Config.TRACKING_SPEED)
                )

                cow.velocity = newVel

                // 设置牛的朝向
                val cowLoc = cow.location
                cowLoc.direction = newVel.clone().normalize()
                cow.teleport(cowLoc)

                // 飞行粒子效果，改用不含火焰的粒子
                cow.world.spawnParticle(
                    Particle.CLOUD,
                    cow.location,
                    5,
                    0.2, 0.2, 0.2,
                    0.01
                )

                // 检查碰撞
                if (cow.location.distance(target.location) < 2.0) {
                    createCowExplosion(cow.location, isRageMode)
                    cow.remove()
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun createCowExplosion(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            // 创建爆炸，设置setFire为false
            createExplosion(
                location,
                if (isRageMode) Config.RAGE_EXPLOSION_POWER else Config.BASE_EXPLOSION_POWER,
                true,  // 破坏方块
                false  // 不产生火焰
            )

            // 爆炸特效
            spawnParticle(
                if (isRageMode) Particle.EXPLOSION_HUGE else Particle.EXPLOSION_LARGE,
                location,
                1,
                0.0, 0.0, 0.0,
                0.0
            )

            // 播放牛死亡音效和爆炸音效
            playSound(
                location,
                Sound.ENTITY_COW_DEATH,
                1.0f,
                if (isRageMode) 0.5f else 1.0f
            )
            playSound(
                location,
                Sound.ENTITY_GENERIC_EXPLODE,
                1.0f,
                if (isRageMode) 0.5f else 1.0f
            )
        }
    }
}