package com.lightload.core;

import net.neoforged.neoforgespi.locating.IModFile;

public interface ModFileBackedResourcePack {
    void lightload$bindModFile(IModFile modFile);

    void lightload$startCachePreload();

    String lightload$cacheIdentity();
}
