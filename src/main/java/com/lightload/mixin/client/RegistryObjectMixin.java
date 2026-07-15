package com.lightload.mixin.client;

import com.lightload.core.ForgeRegistryExistenceCache;
import com.lightload.core.RegistryExistenceAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.RegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = RegistryObject.class, remap = false)
public abstract class RegistryObjectMixin {
    /**
     * Keeps Forge's exact positive lookup semantics while avoiding callback allocation on this startup hot path.
     *
     * @author Lightload
     * @reason Cache registries known to exist without allocating injection callbacks.
     */
    @Overwrite
    private static boolean registryExists(ResourceLocation registryName) {
        if (!ForgeRegistryExistenceCache.enabled()) {
            return RegistryManager.ACTIVE.getRegistry(registryName) != null
                    || BuiltInRegistries.REGISTRY.containsKey(registryName);
        }
        RegistryExistenceAccess cache = (RegistryExistenceAccess) (Object) registryName;
        byte cached = cache.lightload$registryExistence();
        if (cached != 0) {
            ForgeRegistryExistenceCache.recordHit();
            return cached > 0;
        }
        boolean exists = RegistryManager.ACTIVE.getRegistry(registryName) != null
                || BuiltInRegistries.REGISTRY.containsKey(registryName);
        cache.lightload$registryExistence((byte) (exists ? 1 : -1));
        ForgeRegistryExistenceCache.recordMiss(exists);
        return exists;
    }
}
