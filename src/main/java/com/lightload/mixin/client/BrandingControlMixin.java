package com.lightload.mixin.client;

import com.lightload.core.StartupCoordinator;
import net.minecraftforge.common.ForgeI18n;
import net.minecraftforge.internal.BrandingControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = BrandingControl.class, remap = false)
public abstract class BrandingControlMixin {
    @Inject(method = "getBrandings", at = @At("RETURN"), cancellable = true, require = 0)
    private static void lightload$addStartupTime(boolean includeMinecraft, boolean reverse,
                                                  CallbackInfoReturnable<List<String>> cir) {
        if (!StartupCoordinator.shouldShowLoadingTime()) return;

        String startupTime = ForgeI18n.parseMessage(
                "lightload.startup_time",
                StartupCoordinator.completedStartupTimeText()
        );
        List<String> original = cir.getReturnValue();
        List<String> withStartupTime = new ArrayList<>(original.size() + 1);
        if (reverse) {
            withStartupTime.addAll(original);
            withStartupTime.add(startupTime);
        } else {
            withStartupTime.add(startupTime);
            withStartupTime.addAll(original);
        }
        cir.setReturnValue(List.copyOf(withStartupTime));
    }
}
