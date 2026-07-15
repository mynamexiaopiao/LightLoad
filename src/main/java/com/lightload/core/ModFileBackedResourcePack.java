package com.lightload.core;

import net.minecraftforge.forgespi.locating.IModFile;

public interface ModFileBackedResourcePack {
    void lightload$bindModFile(IModFile modFile);

    void lightload$startCachePreload();

    String lightload$cacheIdentity();
}
