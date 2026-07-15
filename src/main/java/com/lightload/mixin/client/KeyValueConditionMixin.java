package com.lightload.mixin.client;

import com.google.common.base.Splitter;
import com.lightload.core.StartupCoordinator;
import net.minecraft.client.renderer.block.model.multipart.KeyValueCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(value = KeyValueCondition.class, priority = Integer.MAX_VALUE)
public abstract class KeyValueConditionMixin {
    @Unique
    private static final ConcurrentMap<CharSequence, List<String>> LIGHTLOAD_SPLITS = new ConcurrentHashMap<>();

    @Redirect(
            method = "getPredicate",
            at = @At(value = "INVOKE", target = "Lcom/google/common/base/Splitter;splitToList(Ljava/lang/CharSequence;)Ljava/util/List;", remap = false)
    )
    private List<String> lightload$splitCached(Splitter splitter, CharSequence sequence) {
        if (!StartupCoordinator.optimizationsActive()) return splitter.splitToList(sequence);
        return LIGHTLOAD_SPLITS.computeIfAbsent(sequence, splitter::splitToList);
    }
}
