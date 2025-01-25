package net.dabbit.supercow.combat

import net.dabbit.supercow.SuperCow
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.random.Random

class ProjectileManager(private val plugin: SuperCow) {

    object Config {
        // 通用配置
        const val MAX_LIFETIME_TICKS = 100
        const val TRACKING_SPEED = 0.15
        const val BASE_VELOCITY = 1.2
        const val RAGE_VELOCITY = 1.8

        // 药水配置
        const val POTION_GRAVITY = 0.05
        const val SPLASH_RADIUS = 4.0
        val POTION_EFFECTS = mapOf(
//            PotionEffectType.POISON to Pair(200, 2),      // 中毒 10秒，等级2
            PotionEffectType.WITHER to Pair(20, 1),      // 凋零 5秒，等级1
            PotionEffectType.SLOW to Pair(200, 2),        // 缓慢 5秒，等级2
            PotionEffectType.WEAKNESS to Pair(160, 2),    // 虚弱 8秒，等级2
//            PotionEffectType.HEAL to Pair(50, 1),   // 反胃 5秒，等级1
//            PotionEffectType.HEALTH_BOOST to Pair(50, 1),   // 反胃 5秒，等级1
        )

        // 火球配置
        const val FIREBALL_EXPLOSION_POWER = 1.5f
        const val RAGE_FIREBALL_EXPLOSION_POWER = 2.5f
        const val FIREBALL_FIRE_TICKS = 60               // 燃烧3秒
        const val RAGE_FIREBALL_FIRE_TICKS = 100        // 燃烧5秒
    }

    fun shootPotion(shootLocation: Location, target: Entity, isRageMode: Boolean) {
        val potion = shootLocation.world?.spawn(
            shootLocation.clone().add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0),
            ThrownPotion::class.java
        ) ?: return

        // 设置药水物品
        val potionItem = ItemStack(Material.SPLASH_POTION)
        val potionMeta = potionItem.itemMeta as PotionMeta

        // 根据狂暴模式选择药水颜色
        potionMeta.color = if (isRageMode) {
            Color.fromRGB(128, 0, 128) // 紫色
        } else {
            Color.fromRGB(75, 0, 130)  // 靛蓝色
        }

        // 添加随机药水效果
        val selectedEffects = Config.POTION_EFFECTS.entries
            .shuffled()
            .take(if (isRageMode) 3 else 2)

        selectedEffects.forEach { (effectType, durationAmplifier) ->
            val (duration, amplifier) = durationAmplifier
            potionMeta.addCustomEffect(
                PotionEffect(
                    effectType,
                    if (isRageMode) duration * 3/2 else duration,
                    if (isRageMode) amplifier + 1 else amplifier
                ),
                true
            )
        }

        potion.item = potionItem

        // 设置初始速度和方向
        val initialDirection = calculateInitialDirection(shootLocation, target.location)
        potion.velocity = initialDirection.multiply(if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY)

        // 添加自定义标签
        potion.setMetadata("supercow_potion", plugin.fixedMetadataValue())

        // 播放发射效果
        playPotionLaunchEffects(shootLocation, isRageMode)

        // 追踪控制
        startPotionTracking(potion, target, isRageMode)
    }

    fun shootFireball(shootLocation: Location, target: Entity, isRageMode: Boolean) {
        val fireball = shootLocation.world?.spawn(
            shootLocation.clone().add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0),
            SmallFireball::class.java
        ) ?: return

        // 设置火球属性
        fireball.yield = if (isRageMode) Config.RAGE_FIREBALL_EXPLOSION_POWER else Config.FIREBALL_EXPLOSION_POWER
        fireball.setIsIncendiary(false)  // 设置为false防止点燃

        // 设置初始速度和方向
        val initialDirection = calculateInitialDirection(shootLocation, target.location)
        fireball.direction = initialDirection
        fireball.velocity = initialDirection.multiply(if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY)

        // 添加自定义标签
        fireball.setMetadata("supercow_fireball", plugin.fixedMetadataValue())

        // 播放发射效果
        playFireballLaunchEffects(shootLocation, isRageMode)

        // 追踪控制
        startFireballTracking(fireball, target, isRageMode)
    }
    @EventHandler
    fun onFireballHit(event: EntityExplodeEvent) {
        val entity = event.entity
        if (entity !is SmallFireball || !entity.hasMetadata("supercow_fireball")) {
            return
        }

        // 阻止方块燃烧
        event.blockList().clear()  // 可选：如果你不想破坏方块

        // 创建不带火焰的爆炸效果
        entity.world.createExplosion(
            entity.location,
            entity.yield,
            false,  // 不产生火焰
            true    // 破坏方块（可以根据需要设置为false）
        )

        event.isCancelled = true  // 取消原始爆炸
    }

    private fun calculateInitialDirection(from: Location, to: Location): Vector {
        val direction = to.clone().subtract(from).toVector().normalize()

        // 添加一些随机偏移
        val spread = 0.1
        direction.x += Random.nextDouble(-spread, spread)
        direction.y += Random.nextDouble(-spread, spread)
        direction.z += Random.nextDouble(-spread, spread)

        return direction.normalize()
    }

    private fun playPotionLaunchEffects(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            playSound(
                location,
                Sound.ENTITY_WITCH_THROW,
                1.0f,
                if (isRageMode) 0.5f else 1.0f
            )
            spawnParticle(
                if (isRageMode) Particle.SPELL_WITCH else Particle.SPELL,
                location.add(0.0, 1.0, 0.0),
                15,
                0.2, 0.2, 0.2,
                0.05
            )
        }
    }

    private fun playFireballLaunchEffects(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            playSound(
                location,
                Sound.ENTITY_BLAZE_SHOOT,
                1.0f,
                if (isRageMode) 0.5f else 1.0f
            )
            spawnParticle(
                if (isRageMode) Particle.FLAME else Particle.SMOKE_NORMAL,
                location.add(0.0, 1.0, 0.0),
                20,
                0.2, 0.2, 0.2,
                0.05
            )
        }
    }

    private fun startPotionTracking(potion: ThrownPotion, target: Entity, isRageMode: Boolean) {
        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                ticks++

                if (!potion.isValid || ticks >= Config.MAX_LIFETIME_TICKS) {
                    if (potion.isValid) potion.remove()
                    cancel()
                    return
                }

                if (!target.isValid || target.isDead) {
                    potion.remove()
                    cancel()
                    return
                }

                // 更新药水轨迹
                val currentVel = potion.velocity
                val targetDir = target.location.clone()
                    .subtract(potion.location)
                    .toVector()
                    .normalize()
                    .multiply(if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY)

                // 应用重力
                currentVel.y -= Config.POTION_GRAVITY

                // 平滑转向
                val newVel = currentVel.add(
                    targetDir.subtract(currentVel).multiply(Config.TRACKING_SPEED)
                )

                potion.velocity = newVel

                // 轨迹粒子效果
                potion.world.spawnParticle(
                    if (isRageMode) Particle.DRAGON_BREATH else Particle.SPELL_MOB,
                    potion.location,
                    3,
                    0.1, 0.1, 0.1,
                    0.01
                )
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun startFireballTracking(fireball: SmallFireball, target: Entity, isRageMode: Boolean) {
        object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                ticks++

                if (!fireball.isValid || ticks >= Config.MAX_LIFETIME_TICKS) {
                    if (fireball.isValid) {
                        createExplosion(fireball.location, isRageMode)
                        fireball.remove()
                    }
                    cancel()
                    return
                }

                if (!target.isValid || target.isDead) {
                    createExplosion(fireball.location, isRageMode)
                    fireball.remove()
                    cancel()
                    return
                }

                // 更新火球轨迹
                val targetDir = target.location.clone()
                    .subtract(fireball.location)
                    .toVector()
                    .normalize()
                    .multiply(if (isRageMode) Config.RAGE_VELOCITY else Config.BASE_VELOCITY)

                fireball.direction = targetDir
                fireball.velocity = targetDir

                // 轨迹粒子效果
                fireball.world.spawnParticle(
                    if (isRageMode) Particle.FLAME else Particle.SMOKE_NORMAL,
                    fireball.location,
                    5,
                    0.1, 0.1, 0.1,
                    0.01
                )
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun createExplosion(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            if (isRageMode) {
                createExplosion(
                    location,
                    Config.RAGE_FIREBALL_EXPLOSION_POWER,
                    true,
                    true
                )
            } else {
                createExplosion(
                    location,
                    Config.FIREBALL_EXPLOSION_POWER,
                    true,
                    false
                )
            }

            // 额外的爆炸效果
            spawnParticle(
                if (isRageMode) Particle.EXPLOSION_HUGE else Particle.EXPLOSION_LARGE,
                location,
                1,
                0.0, 0.0, 0.0,
                0.0
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