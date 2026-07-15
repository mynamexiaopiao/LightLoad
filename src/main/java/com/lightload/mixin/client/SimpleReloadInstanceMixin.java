package com.lightload.mixin.client;

import com.lightload.core.ResourceReloadFailureGuard;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin {
    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static List<PreparableReloadListener> lightload$guardListeners(
            List<PreparableReloadListener> listeners) {
        return ResourceReloadFailureGuard.wrap(listeners);
    }
}
