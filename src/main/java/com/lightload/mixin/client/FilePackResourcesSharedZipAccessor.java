package com.lightload.mixin.client;

import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import javax.annotation.Nullable;
import java.io.File;
import java.util.zip.ZipFile;

@Mixin(FilePackResources.SharedZipFileAccess.class)
public interface FilePackResourcesSharedZipAccessor {
    @Invoker("getOrCreateZipFile")
    @Nullable
    ZipFile lightload$invokeGetOrCreateZipFile();

    @Accessor("zipFile")
    void lightload$setZipFile(@Nullable ZipFile zipFile);

    @Accessor("file")
    File lightload$file();
}
