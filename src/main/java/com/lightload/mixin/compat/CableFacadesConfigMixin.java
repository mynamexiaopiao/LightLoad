package com.lightload.mixin.compat;

import com.lightload.core.StartupNetworkGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "com.portingdeadmods.cable_facades.CFConfig", remap = false)
public abstract class CableFacadesConfigMixin {
    @Inject(
            method = "downloadListFromGithub(Ljava/lang/String;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private static void lightload$deferRemoteList(String listType,
                                                   CallbackInfoReturnable<List<String>> cir) {
        if (StartupNetworkGuard.deferCableFacadesRequest(listType)) {
            cir.setReturnValue(List.of());
        }
    }
}
