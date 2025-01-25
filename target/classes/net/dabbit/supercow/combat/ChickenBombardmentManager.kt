package net.dabbit.supercow.combat

import net.dabbit.supercow.SuperCow
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Chicken
import org.bukkit.entity.Egg
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.random.Random

class ChickenBombardmentManager(private val plugin: SuperCow) {

    object Config {
        // 通用配置
        const val ATTACK_HEIGHT = 20.0 // 轰炸高度
        const val BASE_CHICKEN_COUNT = 5 // 基础鸡的数量
        const val RAGE_CHICKEN_COUNT = 12 // 狂暴模式鸡的数量
        const val EGG_BURST_COUNT = 6 // 每只鸡释放的鸡蛋数量

        // 伤害配置
        const val BASE_DAMAGE = 4.0 // 基础伤害
        const val RAGE_DAMAGE = 7.0 // 狂暴伤害

        // 效果范围
        const val EFFECT_RADIUS = 3.0 // 效果范围

        // 负面效果
        val DEBUFF_EFFECTS = mapOf(
            PotionEffectType.SLOW to Pair(60, 1),        // 减速
            PotionEffectType.CONFUSION to Pair(100, 1),  // 反胃
            PotionEffectType.BLINDNESS to Pair(40, 1),   // 失明
            PotionEffectType.WEAKNESS to Pair(80, 1)     // 虚弱
        )
    }

    fun startChickenBombardment(targetLocation: Location, target: Entity, isRageMode: Boolean) {
        val chickenCount = if (isRageMode) Config.RAGE_CHICKEN_COUNT else Config.BASE_CHICKEN_COUNT

        // 播放开始音效
        targetLocation.world?.playSound(
            targetLocation,
            Sound.ENTITY_CHICKEN_AMBIENT,
            1.0f,
            0.5f
        )

        // 生成多只轰炸鸡
        repeat(chickenCount) { index ->
            spawnBomberChicken(targetLocation, target, isRageMode, index * 5L)
        }
    }

    private fun spawnBomberChicken(targetLocation: Location, target: Entity, isRageMode: Boolean, delay: Long) {
        object : BukkitRunnable() {
            override fun run() {
                // 在目标上方生成鸡
                val spawnLocation = targetLocation.clone().add(
                    Random.nextDouble(-5.0, 5.0),
                    Config.ATTACK_HEIGHT,
                    Random.nextDouble(-5.0, 5.0)
                )

                val chicken = spawnLocation.world?.spawn(spawnLocation, Chicken::class.java) ?: return

                // 设置鸡的属性
                setupChicken(chicken, isRageMode)

                // 开始投弹任务
                startEggDropping(chicken, target, isRageMode)
            }
        }.runTaskLater(plugin, delay)
    }

    private fun setupChicken(chicken: Chicken, isRageMode: Boolean) {
        chicken.apply {
            isGlowing = true
            setAI(false)
            isInvulnerable = true
            customName = if (isRageMode) "§c愤怒轰炸鸡" else "§e轰炸鸡"
            isCustomNameVisible = true
        }
    }

    private fun startEggDropping(chicken: Chicken, target: Entity, isRageMode: Boolean) {
        object : BukkitRunnable() {
            private var ticks = 0
            private var eggsDropped = 0

            override fun run() {
                if (!chicken.isValid || !target.isValid || eggsDropped >= Config.EGG_BURST_COUNT) {
                    // 任务结束，移除鸡并产生特效
                    if (chicken.isValid) {
                        createChickenDisappearEffect(chicken.location)
                        chicken.remove()
                    }
                    cancel()
                    return
                }

                if (ticks % 10 == 0) { // 每10 ticks扔一次蛋
                    launchEggAttack(chicken.location, target.location, isRageMode)
                    eggsDropped++

                    // 播放音效和特效
                    chicken.world.playSound(
                        chicken.location,
                        Sound.ENTITY_CHICKEN_EGG,
                        1.0f,
                        if (isRageMode) 0.5f else 1.0f
                    )
                }

                // 鸡的悬浮粒子效果
                chicken.world.spawnParticle(
                    if (isRageMode) Particle.FLAME else Particle.CLOUD,
                    chicken.location,
                    3,
                    0.2, 0.2, 0.2,
                    0.01
                )

                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun launchEggAttack(from: Location, targetLoc: Location, isRageMode: Boolean) {
        val egg = from.world?.spawn(from, Egg::class.java) ?: return

        // 添加元数据以标识这是特殊的攻击蛋
        egg.setMetadata("supercow_egg", plugin.fixedMetadataValue())

        // 计算向量并设置速度
        val direction = targetLoc.clone().subtract(from).toVector().normalize()
        // 添加一些随机偏移
        direction.add(Vector(
            Random.nextDouble(-0.1, 0.1),
            Random.nextDouble(-0.1, 0.1),
            Random.nextDouble(-0.1, 0.1)
        ))

        egg.velocity = direction.multiply(1.5)

        // 跟踪蛋的轨迹并产生特效
        object : BukkitRunnable() {
            override fun run() {
                if (!egg.isValid) {
                    cancel()
                    return
                }

                // 轨迹粒子效果
                egg.world.spawnParticle(
                    if (isRageMode) Particle.SPELL_WITCH else Particle.SPELL,
                    egg.location,
                    1,
                    0.0, 0.0, 0.0,
                    0.01
                )
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun createChickenDisappearEffect(location: Location) {
        location.world?.apply {
            // 播放消失音效
            playSound(
                location,
                Sound.ENTITY_CHICKEN_HURT,
                1.0f,
                1.2f
            )

            // 羽毛粒子效果
            spawnParticle(
                Particle.CLOUD,
                location,
                20,
                0.3, 0.3, 0.3,
                0.1
            )
        }
    }

    // 当鸡蛋击中时调用此方法（需要在事件监听器中实现）
    fun handleEggImpact(eggLocation: Location, isRageMode: Boolean) {
        // 创建着陆效果
        createEggImpactEffects(eggLocation, isRageMode)

        // 对范围内的实体造成伤害和效果
        eggLocation.world?.getNearbyEntities(
            eggLocation,
            Config.EFFECT_RADIUS,
            Config.EFFECT_RADIUS,
            Config.EFFECT_RADIUS
        )?.forEach { entity ->
            if (entity.type != EntityType.PLAYER) return@forEach

            // 造成伤害
            if (entity is org.bukkit.entity.Damageable) {
                entity.damage(if (isRageMode) Config.RAGE_DAMAGE else Config.BASE_DAMAGE)
            }

            // 添加随机负面效果
            if (entity is org.bukkit.entity.LivingEntity) {
                val randomEffect = Config.DEBUFF_EFFECTS.entries.random()
                val (duration, amplifier) = randomEffect.value
                entity.addPotionEffect(PotionEffect(
                    randomEffect.key,
                    if (isRageMode) duration * 3/2 else duration,
                    if (isRageMode) amplifier + 1 else amplifier
                ))
            }
        }
    }

    private fun createEggImpactEffects(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            // 播放破碎音效
            playSound(
                location,
                Sound.ENTITY_EGG_THROW,
                1.0f,
                if (isRageMode) 0.5f else 1.0f
            )

            // 粒子效果
            spawnParticle(
                if (isRageMode) Particle.SPELL_WITCH else Particle.SPELL_MOB,
                location,
                25,
                0.5, 0.5, 0.5,
                0.1
            )

            // 额外的蛋液效果
            spawnParticle(
                Particle.ITEM_CRACK,
                location,
                15,
                0.3, 0.3, 0.3,
                0.1
            )
        }
    }
}