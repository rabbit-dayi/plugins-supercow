package net.dabbit.supercow.combat

import net.dabbit.supercow.SuperCow
import org.bukkit.Location
import org.bukkit.util.Vector
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.GameMode
import org.bukkit.entity.*

// TargetValidator.kt
class TargetValidator {

    fun isValidTarget(entity: Entity?, owner: Player?): Boolean {
        if (entity == null) return false

        // 不允许射主人
        if (entity == owner) return false

        return when (entity) {
            is Player -> {
                !(entity.isDead ||
                        !entity.isValid ||
                        entity.isInvulnerable ||
                        entity.gameMode in arrayOf(GameMode.CREATIVE, GameMode.SPECTATOR))
            }
            is LivingEntity -> {
                !(entity.isDead ||
                        !entity.isValid ||
                        entity.isInvulnerable ||
                        entity is ArmorStand ||
                        entity is Villager ||
//                        entity is WanderingTrader ||
                        (entity is Tameable && entity.isTamed) )
            }
            else -> false
        }
    }
}