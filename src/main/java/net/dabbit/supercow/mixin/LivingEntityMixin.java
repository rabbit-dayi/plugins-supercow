package net.dabbit.supercow.mixin;

import net.dabbit.supercow.SuperCowMod;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void supercow$onHurtHead(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        SuperCowMod instance = SuperCowMod.getInstance();
        if (instance != null) {
            LivingEntity self = (LivingEntity) (Object) this;
            if (!instance.onLivingEntityHurt(self, source, amount)) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "die", at = @At("HEAD"))
    private void supercow$onDieHead(DamageSource source, CallbackInfo ci) {
        SuperCowMod instance = SuperCowMod.getInstance();
        if (instance != null) {
            LivingEntity self = (LivingEntity) (Object) this;
            instance.onLivingEntityDie(self, source);
        }
    }
}
