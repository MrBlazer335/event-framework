package net.eventframework.test.callback;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

public interface LivingEntityFallHEADCallback {
    Event<LivingEntityFallHEADCallback> EVENT = EventFactory.createArrayBacked(LivingEntityFallHEADCallback.class,
        (listeners) -> (entity, heightDifference, onGround, state, landedPosition) -> {
            for (LivingEntityFallHEADCallback listener : listeners) {
                ActionResult result = listener.handle(entity, heightDifference, onGround, state, landedPosition);
                if (result != ActionResult.PASS) {
                    return result;
                }
            }
            return ActionResult.PASS;
        });

    ActionResult handle(LivingEntity entity, double heightDifference, boolean onGround,
            BlockState state, BlockPos landedPosition);
}
