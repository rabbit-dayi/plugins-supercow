package net.dabbit.supercow.mixin;

import net.minecraft.world.entity.animal.SnowGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoAI alone does not disable a snow golem's native snow placement. */
@Mixin(SnowGolem.class)
public abstract class SnowGolemMixin {
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void supercow$decorativeSnowman(CallbackInfo ci) {
        SnowGolem self = (SnowGolem) (Object) this;
        if (!self.level.isClientSide && self.getTags().contains("supercow_quirk_actor")) {
            ci.cancel();
        }
    }
}
