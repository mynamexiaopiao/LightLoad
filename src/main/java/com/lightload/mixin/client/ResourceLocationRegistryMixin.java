package com.lightload.mixin.client;

import com.lightload.core.RegistryExistenceAccess;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ResourceLocation.class)
public abstract class ResourceLocationRegistryMixin implements RegistryExistenceAccess {
    @Unique
    private byte lightload$registryExistence;

    @Override
    public byte lightload$registryExistence() {
        return lightload$registryExistence;
    }

    @Override
    public void lightload$registryExistence(byte state) {
        lightload$registryExistence = state;
    }
}
