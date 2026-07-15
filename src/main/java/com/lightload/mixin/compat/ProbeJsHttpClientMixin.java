package com.lightload.mixin.compat;

import com.lightload.core.DeferredHttpClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.http.HttpClient;

@Pseudo
@Mixin(targets = "com.probejs.ProbeJS", remap = false)
public abstract class ProbeJsHttpClientMixin {
    @Redirect(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Ljava/net/http/HttpClient;newHttpClient()Ljava/net/http/HttpClient;"),
            require = 0, remap = false)
    private static HttpClient lightload$deferHttpClientCreation() {
        return DeferredHttpClient.create();
    }
}
