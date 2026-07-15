package com.lightload.mixin.compat;

import com.lightload.core.DeferredHttpClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.http.HttpClient;

@Pseudo
@Mixin(targets = "org.watermedia.youtube.patch.YouTubePatch$WaterMediaDownloader", remap = false)
public abstract class WaterMediaHttpClientMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Ljava/net/http/HttpClient$Builder;build()Ljava/net/http/HttpClient;"),
            require = 0, remap = false)
    private HttpClient lightload$deferHttpClientCreation(HttpClient.Builder builder) {
        return DeferredHttpClient.create(builder);
    }
}
