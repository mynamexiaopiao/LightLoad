package com.lightload.mixin.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Predicate;

@Mixin(targets = "net.minecraft.server.packs.resources.FallbackResourceManager$PackEntry")
public interface FallbackResourceManagerPackEntryAccessor {
    @Invoker("name")
    String lightload$name();

    @Invoker("resources")
    PackResources lightload$resources();

    @Invoker("filter")
    Predicate<ResourceLocation> lightload$filter();

    @Invoker("isFiltered")
    boolean lightload$isFiltered(ResourceLocation location);
}
