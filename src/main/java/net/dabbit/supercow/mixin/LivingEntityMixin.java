package net.dabbit.supercow.mixin;

import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.combat.CombatBalance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    // The only balance multiplier: change the incoming argument once, before vanilla
    // armor/resistance and hurt immunity. Do not scale individual skills as well.
    @ModifyVariable(
        method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
        at = @At("HEAD"), argsOnly = true, ordinal = 0
    )
    private float supercow$balanceOutgoingDamage(float amount, DamageSource source, float originalAmount) {
        return CombatBalance.scaleOutgoingDamage(
            SuperCowMod.getInstance(), (LivingEntity) (Object) this, source, amount
        );
    }

    // Scope native knockback to this exact hurt source; never use victim history.
    @Redirect(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(FDD)V"))
    private void supercow$balanceNativeKnockback(LivingEntity victim, float strength, double x, double z,
            DamageSource source, float amount) {
        victim.knockback((float) (strength * CombatBalance.knockbackFactor(source)), x, z);
    }

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

    /**
     * Safety net for the pet only: vanilla 1.16.5 can starve a mob of health without routing it
     * through {@link LivingEntity#hurt}, which leaves the owner with "no pet" and no death event
     * (observed live: the released cow kept walking around after the owner was told it died).
     * Vanilla heals players with saturation; every other living entity dies at zero health.
     */
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void supercow$watchZeroHealth(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level == null || self.level.isClientSide || self.removed || self.deathTime > 0) {
            return;
        }
        if (self.getHealth() > 0.0F) {
            return;
        }
        if (!(self instanceof net.minecraft.world.entity.animal.Cow)
            || !self.getTags().contains(net.dabbit.supercow.SuperCowMod.TAG_PET)) {
            return;
        }
        SuperCowMod instance = SuperCowMod.getInstance();
        if (instance == null || instance.getOwnerOfPetEntity(self) == null) {
            return;
        }
        self.die(DamageSource.GENERIC);
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
