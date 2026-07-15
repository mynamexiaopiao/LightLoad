package com.lightload.mixin.client;

import com.lightload.core.LazyDataFixerManager;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(DataFixers.class)
public abstract class DataFixersMixin {
    @Shadow
    protected static DataFixer createFixerUpper(Set<DSL.TypeReference> requiredTypes) {
        throw new AssertionError();
    }

    private static boolean lightload$creatingDelegate;

    @Inject(method = "createFixerUpper", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lightload$deferDataFixer(Set<DSL.TypeReference> requiredTypes,
                                                 CallbackInfoReturnable<DataFixer> cir) {
        if (!lightload$creatingDelegate && LazyDataFixerManager.enabled()) {
            cir.setReturnValue(LazyDataFixerManager.create(() -> {
                lightload$creatingDelegate = true;
                try {
                    return createFixerUpper(requiredTypes);
                } finally {
                    lightload$creatingDelegate = false;
                }
            }));
        }
    }
}
