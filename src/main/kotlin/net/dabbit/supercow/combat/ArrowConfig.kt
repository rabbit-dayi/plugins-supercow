package net.dabbit.supercow.combat

// ArrowConfig.kt
class ArrowConfig {
    companion object {
        const val BASE_ARROW_SPEED = 1.2
        const val RAGE_ARROW_SPEED = 2.0
        const val BASE_DAMAGE = 3.0
        const val RAGE_DAMAGE = 6.0
        const val HEAL_AMOUNT = 1.0
        const val SHOOT_HEIGHT = 1.8 // 从牛头部射出
        const val TARGET_HEIGHT = 1.2 // 瞄准目标躯干
        const val GRAVITY = 0.05 // 箭矢重力
        const val SPREAD = 0.2 // 箭矢散布
    }
}