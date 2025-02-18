package net.dabbit.supercow.combat

// ArrowConfig.kt
class ArrowConfig {
    companion object {


        const val SHOOT_HEIGHT = 1.5
        const val BASE_DAMAGE = 1.0
        const val RAGE_DAMAGE = 4.0
        const val HEAL_AMOUNT = 2.5

        const val BASE_ARROW_SPEED = 3.2
        const val SPEED_PER_LEVEL = 0.15  // 每级增加的速度
        const val MAX_LEVEL = 80          // 最大等级
        const val RAGE_MULTIPLIER = 2.0   // 狂暴模式倍率

        fun getBaseSpeed(level: Int): Double {
            val clampedLevel = level.coerceIn(1..MAX_LEVEL)
            return BASE_ARROW_SPEED + (clampedLevel - 1) * SPEED_PER_LEVEL
        }

        // 狂暴模式速度
        fun getRageSpeed(level: Int): Double {
            return getBaseSpeed(level) * RAGE_MULTIPLIER
        }

        const val ARROW_SPEED = 3.0
        const val RAGE_ARROW_SPEED = 6.0
        const val ARROW_SPREAD = 0.0
        const val RAGE_SPREAD = 0.3
        const val FIREWORK_DAMAGE = 3.0
        const val RAGE_FIREWORK_DAMAGE = 6.0
        const val FIREWORK_POWER = 1
        const val RAGE_FIREWORK_POWER = 2
//
//        const val BASE_ARROW_SPEED = 1.2
//        const val RAGE_ARROW_SPEED = 2.0
//        const val BASE_DAMAGE = 3.0
//        const val RAGE_DAMAGE = 6.0
//        const val HEAL_AMOUNT = 1.0
//        const val SHOOT_HEIGHT = 1.8 // 从牛头部射出
        const val TARGET_HEIGHT = 1.2 // 瞄准目标躯干
        const val GRAVITY = 0.05 // 箭矢重力
        const val SPREAD = 0.2 // 箭矢散布

        // 烟花配置
        const val FIREWORK_CHANCE = 0.15 // 15%几率发射烟花
//        const val FIREWORK_POWER = 1 // 烟花飞行距离
//        const val RAGE_FIREWORK_POWER = 2 // 狂暴模式烟花飞行距离
//        const val FIREWORK_DAMAGE = 4.0 // 烟花基础伤害
//        const val RAGE_FIREWORK_DAMAGE = 8.0 // 狂暴模式烟花伤害
//        const val SHOOT_HEIGHT = 1.5
//        const val RAGE_FIREWORK_POWER = 2
//        const val FIREWORK_POWER = 1
    }
}