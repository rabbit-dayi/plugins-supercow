package net.dabbit.supercow.combat

import net.dabbit.supercow.SuperCow
import org.bukkit.Location
import org.bukkit.util.Vector
import org.bukkit.Particle
import org.bukkit.Sound

// ArrowEffectManager.kt
class ArrowEffectManager(private val plugin: SuperCow) {
    fun playShootEffects(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            // 基础音效
            playSound(location, Sound.ENTITY_ARROW_SHOOT, 1.0f, if (isRageMode) 1.5f else 1.0f)

            if (isRageMode) {
                // 狂暴模式特效
                spawnParticle(
                    Particle.EXPLOSION_LARGE,
                    location.clone().add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0),
                    3, 0.2, 0.2, 0.2, 0.1
                )
                spawnParticle(
                    Particle.FLAME,
                    location.clone().add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0),
                    15, 0.3, 0.3, 0.3, 0.05
                )
                playSound(location, Sound.ENTITY_BLAZE_SHOOT, 0.5f, 1.2f)
            } else {
                // 普通模式特效
                spawnParticle(
                    Particle.CRIT,
                    location.clone().add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0),
                    10, 0.2, 0.2, 0.2, 0.1
                )
                spawnParticle(
                    Particle.END_ROD,
                    location.clone().add(0.0, ArrowConfig.SHOOT_HEIGHT, 0.0),
                    5, 0.1, 0.1, 0.1, 0.05
                )
            }
        }
    }

    fun playHitEffects(location: Location, isRageMode: Boolean) {
        location.world?.apply {
            playSound(location, Sound.ENTITY_ARROW_HIT, 1.0f, 1.0f)

            if (isRageMode) {
                spawnParticle(
                    Particle.EXPLOSION_NORMAL,
                    location,
                    5, 0.2, 0.2, 0.2, 0.1
                )
            } else {
                spawnParticle(
                    Particle.CRIT_MAGIC,
                    location,
                    8, 0.2, 0.2, 0.2, 0.1
                )
            }
        }
    }
}