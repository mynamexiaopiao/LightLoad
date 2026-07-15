package com.lightload.mixin.client;

import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Function;

@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(StateDefinition.class)
public abstract class StateDefinitionMixin {
    @Unique
    private Map<String, Property<?>> lightload$propertiesByName;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void lightload$captureProperties(Function ownerToState, Object owner,
                                             StateDefinition.Factory factory, Map properties,
                                             CallbackInfo ci) {
        lightload$propertiesByName = properties;
    }

    @Inject(method = "getProperty", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$getProperty(String name, CallbackInfoReturnable<Property<?>> cir) {
        Map<String, Property<?>> properties = lightload$propertiesByName;
        if (properties != null) cir.setReturnValue(properties.get(name));
    }
}
