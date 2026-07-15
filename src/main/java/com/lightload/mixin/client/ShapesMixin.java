package com.lightload.mixin.client;

import com.lightload.core.VoxelShapeStartupCache;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Shapes.class)
public abstract class ShapesMixin {
    @Inject(method = "box", at = @At("HEAD"), cancellable = true)
    private static void lightload$findBox(double minX, double minY, double minZ,
                                          double maxX, double maxY, double maxZ,
                                          CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape cached = VoxelShapeStartupCache.findBox(minX, minY, minZ, maxX, maxY, maxZ);
        if (cached != null) cir.setReturnValue(cached);
    }

    @Inject(method = "box", at = @At("RETURN"), cancellable = true)
    private static void lightload$cacheBox(double minX, double minY, double minZ,
                                           double maxX, double maxY, double maxZ,
                                           CallbackInfoReturnable<VoxelShape> cir) {
        cir.setReturnValue(VoxelShapeStartupCache.cacheBox(minX, minY, minZ, maxX, maxY, maxZ,
                cir.getReturnValue()));
    }

    @Inject(method = "joinUnoptimized", at = @At("HEAD"), cancellable = true)
    private static void lightload$findJoin(VoxelShape first, VoxelShape second, BooleanOp operation,
                                           CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape cached = VoxelShapeStartupCache.findJoin(first, second, operation);
        if (cached != null) cir.setReturnValue(cached);
    }

    @Inject(method = "joinUnoptimized", at = @At("RETURN"), cancellable = true)
    private static void lightload$cacheJoin(VoxelShape first, VoxelShape second, BooleanOp operation,
                                            CallbackInfoReturnable<VoxelShape> cir) {
        cir.setReturnValue(VoxelShapeStartupCache.cacheJoin(first, second, operation, cir.getReturnValue()));
    }
}
