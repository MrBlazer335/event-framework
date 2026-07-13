package net.eventframework.test.mixin;

import net.eventframework.test.callback.LivingEntityFallHEADCallback;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityFallHEADMixin {
    @Inject(
            method = "fall",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onFall(double heightDifference, boolean onGround, BlockState state,
            BlockPos landedPosition, CallbackInfo ci) {
        ActionResult result = LivingEntityFallHEADCallback.EVENT.invoker().handle((net.minecraft.entity.LivingEntity)(Object) this, heightDifference, onGround, state, landedPosition);
        if (result == ActionResult.FAIL) {
            ci.cancel();
        }
    }
}
