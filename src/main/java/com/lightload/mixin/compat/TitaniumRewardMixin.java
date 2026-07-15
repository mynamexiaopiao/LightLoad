package com.lightload.mixin.compat;

import com.lightload.core.StartupNetworkGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.hrznstudio.titanium.reward.Reward", remap = false)
public abstract class TitaniumRewardMixin {
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V"),
            require = 0,
            remap = false
    )
    private void lightload$deferRewardDownload(Thread thread) {
        if (!StartupNetworkGuard.deferTitaniumRewardRequest(thread)) thread.start();
    }
}
