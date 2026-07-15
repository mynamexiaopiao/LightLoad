package com.lightload.mixin.client;

import com.lightload.core.LazyDataFixerManager;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.types.Type;
import net.minecraft.Util;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockEntityType.class)
public abstract class BlockEntityTypeMixin {
    @Redirect(method = "register", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;fetchChoiceType(Lcom/mojang/datafixers/DSL$TypeReference;Ljava/lang/String;)Lcom/mojang/datafixers/types/Type;"), require = 0)
    private static Type<?> lightload$skipEagerSchemaCheck(DSL.TypeReference type, String id) {
        return LazyDataFixerManager.enabled() ? null : Util.fetchChoiceType(type, id);
    }
}
