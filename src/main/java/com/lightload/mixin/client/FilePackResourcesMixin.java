package com.lightload.mixin.client;

import com.lightload.cache.ZipResourceIndexStore;
import com.lightload.core.FusionPackCompat;
import com.lightload.core.StartupCoordinator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Mixin(FilePackResources.class)
public abstract class FilePackResourcesMixin {
    @Shadow @Final private FilePackResources.SharedZipFileAccess zipFileAccess;
    @Shadow @Final private String prefix;

    @Unique
    private volatile Map<PackType, List<String>> lightload$entriesByType;

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$listCachedEntries(PackType type, String namespace, String path,
                                             PackResources.ResourceOutput output, CallbackInfo ci) {
        if (!StartupCoordinator.optimizationsActive() || FusionPackCompat.hasOverrides(this)) return;
        ZipFile zip = lightload$openZip();
        if (zip == null) return;
        String namespaceRoot = lightload$addPrefix(type.getDirectory() + "/" + namespace + "/");
        String requestedRoot = namespaceRoot + (path.isEmpty() ? "" : path + "/");
        List<String> entries = lightload$entriesByType().computeIfAbsent(type,
                ignored -> lightload$loadEntries(type, zip));
        int first = Collections.binarySearch(entries, requestedRoot);
        if (first < 0) first = -first - 1;
        for (int index = first; index < entries.size(); index++) {
            String entry = entries.get(index);
            if (!entry.startsWith(requestedRoot)) break;
            ResourceLocation location = ResourceLocation.tryBuild(namespace, entry.substring(namespaceRoot.length()));
            if (location != null) output.accept(location, lightload$openResource(location, entry));
        }
        ci.cancel();
    }

    @Unique
    private ZipFile lightload$openZip() {
        FilePackResourcesSharedZipAccessor access = (FilePackResourcesSharedZipAccessor) zipFileAccess;
        ZipFile zip = access.lightload$invokeGetOrCreateZipFile();
        if (zip == null || lightload$isOpen(zip)) return zip;
        access.lightload$setZipFile(null);
        zip = access.lightload$invokeGetOrCreateZipFile();
        return zip != null && lightload$isOpen(zip) ? zip : null;
    }

    @Unique
    private IoSupplier<InputStream> lightload$openResource(ResourceLocation location, String entryPath) {
        return () -> {
            ZipFile zip = lightload$openZip();
            if (zip == null) throw new FileNotFoundException(location.toString());
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null) throw new FileNotFoundException(location.toString());
            return zip.getInputStream(entry);
        };
    }

    @Unique
    private String lightload$addPrefix(String path) {
        return prefix.isEmpty() ? path : prefix + "/" + path;
    }

    @Unique
    private static boolean lightload$isOpen(ZipFile zip) {
        try {
            zip.size();
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    @Unique
    private Map<PackType, List<String>> lightload$entriesByType() {
        Map<PackType, List<String>> entries = lightload$entriesByType;
        if (entries != null) return entries;
        synchronized (this) {
            if (lightload$entriesByType == null) lightload$entriesByType = new ConcurrentHashMap<>();
            return lightload$entriesByType;
        }
    }

    @Unique
    private List<String> lightload$loadEntries(PackType type, ZipFile zip) {
        FilePackResourcesSharedZipAccessor access = (FilePackResourcesSharedZipAccessor) zipFileAccess;
        String archive = ZipResourceIndexStore.fingerprint(access.lightload$file(), prefix);
        List<String> cached = ZipResourceIndexStore.find(StartupCoordinator.cacheDirectory(), archive, type.name());
        if (cached != null) return cached;
        List<String> scanned = zip.stream().filter(entry -> !entry.isDirectory())
                .map(ZipEntry::getName).sorted().toList();
        ZipResourceIndexStore.record(StartupCoordinator.cacheDirectory(), archive, type.name(), scanned);
        ZipResourceIndexStore.persistAsync(StartupCoordinator.cacheDirectory());
        return scanned;
    }
}
