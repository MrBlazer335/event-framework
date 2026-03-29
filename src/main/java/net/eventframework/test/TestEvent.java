package net.eventframework.test;

import net.eventframework.annotation.FabricEvent;
import net.eventframework.annotation.HandleEvent;
import net.eventframework.annotation.InjectionPosition;

import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;


//@FabricEvent(LivingEntity.class)
//public class TestEvent {
//    public static void handle() {
//
//    }
//    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
//    public static ActionResult fall(double heightDifference, boolean onGround, BlockState state, BlockPos landedPosition, LivingEntity entity) {
//        // fall() is called every tick — actual fall distance is stored in entity.fallDistance
//        // damage only happens when onGround=true and fallDistance > 3.0
//        if (!onGround) return ActionResult.PASS;
//        if (!(entity instanceof PlayerEntity player)) return ActionResult.PASS;
//
//        // fallDistance is the accumulated fall — access it via the entity reference
//        float totalFall = (float) entity.fallDistance;
//        System.out.println("Player landed! totalFall=" + totalFall);
//
//        if (totalFall <= 3.0f) return ActionResult.PASS;
//
//        player.addExperience(100);
//        return ActionResult.PASS; // PASS — let vanilla damage apply too
//    }
//}
