package net.dabbit.supercow.mixin;

import net.dabbit.supercow.SuperCowMod;
import net.dabbit.supercow.gui.PetGui;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right-click entry point for the pet GUI.
 *
 * Fabric's UseEntityCallback (fabric-events-interaction-v0) is the idiomatic hook, but on the
 * 1.16.5 dedicated server used for testing its injection never reached our listener, so the
 * interaction is handled here as well. Whichever hook fires first opens the panel; the other
 * path is a no-op because the packet is cancelled.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerMixin {

    @Shadow
    private ServerPlayer player;

    /**
     * Publishes the client's predicted carried stack for the click that is about to be handled.
     *
     * <p>1.16.5 validates a click by comparing {@code menu.clicked(...)}'s return value with the
     * stack the client says it is holding afterwards. A mismatch triggers a rejected ack, a
     * re-push of the whole window and {@code setSynched(player, false)}; a real client answering a
     * rejection while the mouse is still down ends up with a ghost cursor item and a window that
     * ignores every following click. Returning the prediction verbatim keeps the ack positive.</p>
     */
    @Inject(method = "handleContainerClick", at = @At("HEAD"))
    private void supercow$rememberPredictedCarried(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        PetGui.setPredictedCarried(packet.getItem());
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void supercow$handleInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (player == null || player.level == null || player.level.isClientSide) {
            return;
        }
        if (packet.getAction() != ServerboundInteractPacket.Action.INTERACT) {
            return;
        }
        Entity entity = packet.getTarget(player.level);
        if (!(entity instanceof Cow)) {
            return;
        }
        InteractionHand hand = packet.getHand();
        boolean consumed = SuperCowMod.getInstance().handleCowRightClick(player, (Cow) entity, hand);
        if (consumed) {
            ci.cancel();
        }
    }
}
