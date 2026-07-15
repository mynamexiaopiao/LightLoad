package com.lightload.mixin.client;

import com.lightload.LightloadMod;
import com.lightload.core.CompatibilityManager;
import com.lightload.core.FastTexturePacker;
import com.lightload.core.StartupCoordinator;
import net.minecraft.client.renderer.texture.Stitcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mixin(Stitcher.class)
public abstract class StitcherMixin<T extends Stitcher.Entry> {
    @Shadow @Final private List<Stitcher.Holder<T>> texturesToBeStitched;
    @Shadow @Final private int maxWidth;
    @Shadow @Final private int maxHeight;
    @Shadow private int storageX;
    @Shadow private int storageY;
    @Shadow @Final private static Comparator<Stitcher.Holder<?>> HOLDER_COMPARATOR;

    @Unique private FastTexturePacker.Result<T> lightload$result;

    @Inject(method = "stitch", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$fastStitch(CallbackInfo ci) {
        var config = StartupCoordinator.config();
        if (!StartupCoordinator.aggressiveOptimizationsEnabled() || !config.fastTextureStitching() || texturesToBeStitched.size() < 100
                || CompatibilityManager.modernFixPresent() || CompatibilityManager.optiFinePresent()) return;
        try {
            List<Stitcher.Holder<T>> sorted = new ArrayList<>(texturesToBeStitched);
            sorted.sort((left, right) -> HOLDER_COMPARATOR.compare(left, right));
            FastTexturePacker.Result<T> result = FastTexturePacker.pack(sorted, maxWidth, maxHeight);
            if (result == null) return;
            lightload$result = result;
            storageX = result.width();
            storageY = result.height();
            ci.cancel();
        } catch (RuntimeException | LinkageError error) {
            lightload$result = null;
            LightloadMod.LOGGER.warn("[Lightload] Fast texture packing failed; using vanilla Stitcher", error);
        }
    }

    @Inject(method = "gatherSprites", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$gatherFast(Stitcher.SpriteLoader<T> loader, CallbackInfo ci) {
        FastTexturePacker.Result<T> result = lightload$result;
        if (result == null) return;
        ci.cancel();
        result.placements().forEach(placement -> loader.load(placement.entry(), placement.x(), placement.y()));
    }
}
