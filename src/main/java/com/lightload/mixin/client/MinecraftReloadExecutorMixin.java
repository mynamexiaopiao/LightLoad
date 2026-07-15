package com.lightload.mixin.client;

import com.lightload.core.ReloadExecutorManager;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ExecutorService;

@Mixin(Minecraft.class)
public abstract class MinecraftReloadExecutorMixin {
    @Redirect(method = {"<init>", "reloadResourcePacks(ZLnet/minecraft/client/Minecraft$GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;backgroundExecutor()Ljava/util/concurrent/ExecutorService;", ordinal = 0), require = 0)
    private ExecutorService lightload$dedicatedReloadExecutor() {
        return ReloadExecutorManager.select(Util.backgroundExecutor());
    }
}
