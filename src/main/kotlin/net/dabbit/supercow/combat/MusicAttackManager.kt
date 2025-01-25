package net.dabbit.supercow.combat

import net.dabbit.supercow.SuperCow
import org.bukkit.Location
import org.bukkit.Note
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MusicAttackManager(private val plugin: SuperCow) {

    object Config {
        // 音乐配置
        val ATTACK_SOUNDS = listOf(
            Sound.BLOCK_NOTE_BLOCK_BASS,
            Sound.BLOCK_NOTE_BLOCK_SNARE,
            Sound.BLOCK_NOTE_BLOCK_HARP,
            Sound.BLOCK_NOTE_BLOCK_CHIME,
            Sound.BLOCK_NOTE_BLOCK_XYLOPHONE,
            Sound.BLOCK_NOTE_BLOCK_BELL
        )

        // 音高配置 (0-24)
        val NOTE_IDS = listOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23)

        // 音符基础音高
        val BASE_PITCHES = listOf(
            0.5f,  // F#
            0.53f, // G
            0.56f, // G#
            0.6f,  // A
            0.63f, // A#
            0.67f, // B
            0.7f,  // C
            0.75f, // C#
            0.8f,  // D
            0.85f, // D#
            0.9f,  // E
            0.95f, // F
            1.0f   // F#
        )

        // 攻击配置
        const val BASE_DAMAGE = 3.0
        const val RAGE_DAMAGE = 5.0
        const val ATTACK_RADIUS = 8.0
        const val RAGE_ATTACK_RADIUS = 12.0

        // 音波圈数
        const val BASE_WAVE_RINGS = 3
        const val RAGE_WAVE_RINGS = 5

        // 每圈音符数
        const val NOTES_PER_RING = 16

        // 持续时间（ticks）
        const val BASE_DURATION = 100
        const val RAGE_DURATION = 160
    }

    fun startMusicAttack(center: Location, target: Entity, isRageMode: Boolean) {
        val duration = if (isRageMode) Config.RAGE_DURATION else Config.BASE_DURATION
        val rings = if (isRageMode) Config.RAGE_WAVE_RINGS else Config.BASE_WAVE_RINGS

        // 播放开始音效
        center.world?.playSound(
            center,
            Sound.BLOCK_NOTE_BLOCK_PLING,
            2.0f,
            1.0f
        )

        object : BukkitRunnable() {
            private var ticks = 0
            private var currentAngle = 0.0
            private var melodyIndex = 0

            override fun run() {
                if (ticks >= duration) {
                    cancel()
                    return
                }

                // 为每个圆环生成音符
                for (ring in 0 until rings) {
                    val radius = (ring + 1) * 2.0
                    createMusicRing(center, radius, currentAngle, isRageMode)
                }

                // 每tick旋转一定角度
                currentAngle += 5.0
                if (currentAngle >= 360.0) currentAngle = 0.0

                // 检测并伤害范围内的实体
                val attackRadius = if (isRageMode) Config.RAGE_ATTACK_RADIUS else Config.ATTACK_RADIUS
                center.world?.getNearbyEntities(center, attackRadius, attackRadius, attackRadius)
                    ?.filterIsInstance<LivingEntity>()
                    ?.forEach { entity ->
                        if (entity != target) {
                            applyMusicEffects(entity, isRageMode)
                        }
                    }

                melodyIndex = (melodyIndex + 1) % Config.NOTE_IDS.size
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun createMusicRing(center: Location, radius: Double, baseAngle: Double, isRageMode: Boolean) {
        for (i in 0 until Config.NOTES_PER_RING) {
            val angle = baseAngle + (i * 360.0 / Config.NOTES_PER_RING)
            val radians = Math.toRadians(angle)

            val x = center.x + radius * cos(radians)
            val z = center.z + radius * sin(radians)

            val noteLocation = Location(center.world, x, center.y + 0.5, z)

            playMusicNote(noteLocation, isRageMode)
        }
    }

    private fun playMusicNote(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            // 选择随机音色和音高
            val sound = Config.ATTACK_SOUNDS.random()
            val basePitch = Config.BASE_PITCHES.random()

            // 在狂暴模式下添加随机音高变化
            val pitch = if (isRageMode) {
                (basePitch * Random.nextDouble(0.8, 1.2)).toFloat().coerceIn(0.5f, 2.0f)
            } else {
                basePitch
            }

            // 播放音符
            playSound(location, sound, 0.6f, pitch)

            // 生成音符粒子
            spawnParticle(
                if (isRageMode) Particle.NOTE else Particle.VILLAGER_HAPPY,
                location,
                1,
                0.0, 0.0, 0.0,
                1.0
            )

            // 狂暴模式额外特效
            if (isRageMode) {
                spawnParticle(
                    Particle.SPELL_MOB,
                    location,
                    3,
                    0.1, 0.1, 0.1,
                    0.1
                )
            }
        }
    }

    private fun applyMusicEffects(entity: LivingEntity, isRageMode: Boolean) {
        // 造成伤害
        entity.damage(if (isRageMode) Config.RAGE_DAMAGE else Config.BASE_DAMAGE)

        // 添加效果
        val effects = listOf(
            PotionEffect(PotionEffectType.CONFUSION, if (isRageMode) 100 else 60, 0),
            PotionEffect(PotionEffectType.SLOW, if (isRageMode) 40 else 20, 1),
            PotionEffect(PotionEffectType.WEAKNESS, if (isRageMode) 60 else 30, 0)
        )

        // 随机应用一个效果
        entity.addPotionEffect(effects.random())

        // 击退效果
        val knockback = if (isRageMode) 0.8 else 0.4
        val direction = entity.location.toVector().subtract(entity.location.toVector()).normalize()
        entity.velocity = entity.velocity.add(direction.multiply(knockback))
    }

    // 预设音乐主题
    fun playMusicTheme(location: Location, theme: MusicTheme, isRageMode: Boolean) {
        val (sounds, pitches, interval) = when (theme) {
            MusicTheme.HAPPY -> Triple(
                listOf(Sound.BLOCK_NOTE_BLOCK_BELL, Sound.BLOCK_NOTE_BLOCK_CHIME),
                listOf(1.0f, 1.2f, 1.5f, 1.8f),
                4L
            )
            MusicTheme.SCARY -> Triple(
                listOf(Sound.BLOCK_NOTE_BLOCK_BASS, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO),
                listOf(0.5f, 0.6f, 0.7f, 0.8f),
                6L
            )
            MusicTheme.EPIC -> Triple(
                listOf(Sound.BLOCK_NOTE_BLOCK_CHIME, Sound.BLOCK_NOTE_BLOCK_BELL),
                listOf(1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f),
                3L
            )
            MusicTheme.MYSTERIOUS -> Triple(
                listOf(Sound.BLOCK_NOTE_BLOCK_FLUTE, Sound.BLOCK_NOTE_BLOCK_HARP),
                listOf(0.8f, 0.9f, 1.0f, 1.1f),
                5L
            )
        }

        var noteIndex = 0
        object : BukkitRunnable() {
            override fun run() {
                if (noteIndex >= pitches.size * 2) {
                    cancel()
                    return
                }

                val pitch = pitches[noteIndex % pitches.size]
                val sound = sounds.random()

                location.world?.playSound(
                    location,
                    sound,
                    0.8f,
                    if (isRageMode) pitch * Random.nextDouble(0.9, 1.1).toFloat() else pitch
                )

                noteIndex++
            }
        }.runTaskTimer(plugin, 0L, interval)
    }

    enum class MusicTheme {
        HAPPY,
        SCARY,
        EPIC,
        MYSTERIOUS
    }
}