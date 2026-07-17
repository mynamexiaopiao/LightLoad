package com.lightload.mixin.client;

import com.lightload.cache.PackFingerprint;
import com.lightload.cache.ResourceIndexCache;
import com.lightload.core.ModFileBackedResourcePack;
import com.lightload.core.FusionPackCompat;
import com.lightload.core.StartupCoordinator;
import com.lightload.core.SortedStringLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.neoforgespi.locating.IModFile;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Mixin(PathPackResources.class)
public abstract class PathPackResourcesMixin implements ModFileBackedResourcePack {
    @Shadow @Final private Path root;

    @Unique
    private volatile Map<String, Path> lightload$resolvedPaths;
    @Unique
    private IModFile lightload$modFile;
    @Unique
    private String lightload$cacheIdentity;
    @Unique
    private boolean lightload$routeSafe;
    @Unique
    private volatile Set<String> lightload$scheduledPathScans;
    @Unique
    private volatile Map<PackType, Set<String>> lightload$namespaceIndex;
    @Unique
    private volatile Map<String, List<String>> lightload$resourcePathIndex;
    @Unique
    private volatile ResourceIndexCache.PackView lightload$packView;
    @Unique
    private volatile boolean lightload$packViewResolved;

    @Override
    public void lightload$bindModFile(IModFile modFile) {
        lightload$modFile = modFile;
        lightload$cacheIdentity = PackFingerprint.create(lightload$self().packId(), modFile.getFilePath())
                .map(fingerprint -> fingerprint.packId() + '\n' + fingerprint.source() + '\n'
                        + fingerprint.size() + '\n' + fingerprint.modifiedMillis())
                .orElse(null);
        lightload$routeSafe = lightload$cacheIdentity != null && !FusionPackCompat.hasOverrides(this);
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache != null) {
            cache.bindModFile(lightload$self(), modFile);
            lightload$packView = cache.packView(lightload$self());
            lightload$packViewResolved = true;
        }
    }

    @Override
    public void lightload$startCachePreload() {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache != null) {
            cache.startCachePreload(lightload$self());
            lightload$view(cache);
        }
    }

    @Override
    public String lightload$cacheIdentity() {
        return lightload$routeSafe ? lightload$cacheIdentity : null;
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$findNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache == null || !cache.pathIndexEnabled() || FusionPackCompat.hasOverrides(this)) return;
        Set<String> local = lightload$namespaceIndex().get(type);
        if (local != null) {
            cir.setReturnValue(local);
            return;
        }
        ResourceIndexCache.PackView view = lightload$view(cache);
        if (view != null) view.findNamespaces(type).ifPresent(namespaces -> {
            lightload$namespaceIndex().put(type, namespaces);
            cir.setReturnValue(namespaces);
        });
    }

    @Inject(method = "getNamespaces", at = @At("RETURN"), require = 0)
    private void lightload$recordNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        Set<String> namespaces = cir.getReturnValue();
        if (cache != null && cache.pathIndexEnabled() && namespaces != null
                && !FusionPackCompat.hasOverrides(this)) {
            ResourceIndexCache.PackView view = lightload$view(cache);
            if (view == null) return;
            view.recordNamespaces(type, namespaces);
            lightload$namespaceIndex().put(type, namespaces);
            namespaces.forEach(namespace -> lightload$schedulePathScan(cache, type, namespace));
        }
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$listIndexedResources(PackType type, String namespace, String path,
                                                PackResources.ResourceOutput output, CallbackInfo ci) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache == null || !cache.pathIndexEnabled() || FusionPackCompat.hasOverrides(this)) return;
        ResourceIndexCache.PackView view = lightload$view(cache);
        if (view == null) return;
        String indexKey = type.name() + '|' + namespace;
        List<String> indexed = lightload$resourcePathIndex().get(indexKey);
        if (indexed == null) {
            Optional<List<String>> cached = view.findResourcePaths(type, namespace);
            if (cached.isEmpty()) {
                lightload$schedulePathScan(cache, type, namespace);
                return;
            }
            indexed = cached.get();
            lightload$resourcePathIndex().put(indexKey, indexed);
        }

        String requested = path.isEmpty() ? "" : path.endsWith("/") ? path : path + "/";
        int first = requested.isEmpty() ? 0 : SortedStringLookup.lowerBound(indexed, requested);
        for (int index = first; index < indexed.size(); index++) {
            String relative = indexed.get(index);
            if (!relative.startsWith(requested)) {
                if (!requested.isEmpty()) break;
                continue;
            }
            ResourceLocation location = ResourceLocation.tryBuild(namespace, relative);
            if (location != null) output.accept(location,
                    view.wrapContent(type, location, lightload$openResource(type, location)));
        }
        ci.cancel();
    }

    @Unique
    private void lightload$schedulePathScan(ResourceIndexCache cache, PackType type, String namespace) {
        String key = type.name() + '|' + namespace;
        if (!lightload$scheduledPathScans().add(key)) return;
        cache.scanAsync(() -> {
            Path namespaceRoot = lightload$resolve(type.getDirectory(), namespace);
            List<String> paths = lightload$scanPaths(namespaceRoot);
            if (paths != null) cache.recordResourcePaths(lightload$self(), type, namespace, paths);
        });
    }

    @Unique
    private static List<String> lightload$scanPaths(Path root) {
        if (root == null) return Collections.emptyList();
        try (Stream<Path> files = Files.find(root, Integer.MAX_VALUE,
                (candidate, attributes) -> attributes.isRegularFile())) {
            String separator = root.getFileSystem().getSeparator();
            return files.map(root::relativize).map(Path::toString)
                    .map(value -> value.replace(separator, "/")).sorted().toList();
        } catch (java.nio.file.NoSuchFileException ignored) {
            return Collections.emptyList();
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true, require = 1)
    private void lightload$findResource(PackType type, ResourceLocation location,
                                        CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (!StartupCoordinator.optimizationsActive() || cache == null) return;
        ResourceIndexCache.PackView view = lightload$view(cache);
        Optional<Boolean> exists = cache.packExistenceCacheEnabled() && view != null
                ? view.findExistence(type, location)
                : Optional.empty();
        if (exists.isPresent() && !exists.get()) {
            cir.setReturnValue(null);
            return;
        }
        if (!FusionPackCompat.hasOverrides(this)) {
            Optional<IoSupplier<InputStream>> content = cache.findCachedContent(lightload$self(), type, location);
            if (content.isPresent()) {
                cir.setReturnValue(content.get());
                return;
            }
        }
        if (exists.isPresent()) cir.setReturnValue(lightload$openResource(type, location));
    }

    @Inject(method = "getResource", at = @At("RETURN"), cancellable = true, require = 1)
    private void lightload$recordResource(PackType type, ResourceLocation location,
                                          CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (StartupCoordinator.optimizationsActive() && cache != null) {
            IoSupplier<InputStream> original = cir.getReturnValue();
            ResourceIndexCache.PackView view = lightload$view(cache);
            if (cache.packExistenceCacheEnabled() && view != null) {
                view.recordExistence(type, location, original != null);
            }
            if (original != null && !FusionPackCompat.hasOverrides(this)) {
                cir.setReturnValue(view == null ? original : view.wrapContent(type, location, original));
            }
        }
    }

    @Inject(method = "getRootResource", at = @At("HEAD"), cancellable = true, require = 1)
    private void lightload$findRootResource(String[] paths,
                                            CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (!StartupCoordinator.optimizationsActive() || cache == null) return;
        if (!cache.packExistenceCacheEnabled()) return;
        ResourceIndexCache.PackView view = lightload$view(cache);
        if (view == null) return;
        Optional<Boolean> exists = view.findRootExistence(paths);
        if (exists.isEmpty()) return;
        cir.setReturnValue(exists.get() ? lightload$openRootResource(Arrays.copyOf(paths, paths.length)) : null);
    }

    @Inject(method = "getRootResource", at = @At("RETURN"), require = 1)
    private void lightload$recordRootResource(String[] paths,
                                              CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (StartupCoordinator.optimizationsActive() && cache != null && cache.packExistenceCacheEnabled()) {
            ResourceIndexCache.PackView view = lightload$view(cache);
            if (view != null) view.recordRootExistence(paths, cir.getReturnValue() != null);
        }
    }

    @Unique
    private IoSupplier<InputStream> lightload$openResource(PackType type, ResourceLocation location) {
        String[] parts = location.getPath().split("/");
        String[] resolved = new String[parts.length + 2];
        resolved[0] = type.getDirectory();
        resolved[1] = location.getNamespace();
        System.arraycopy(parts, 0, resolved, 2, parts.length);
        return () -> Files.newInputStream(lightload$resolve(resolved));
    }

    @Unique
    private IoSupplier<InputStream> lightload$openRootResource(String[] paths) {
        return () -> {
            Path resolved = lightload$resolve(paths);
            if (resolved == null) throw new NoSuchFileException(Arrays.toString(paths));
            return Files.newInputStream(resolved);
        };
    }

    @Unique
    private PathPackResources lightload$self() {
        return (PathPackResources) (Object) this;
    }

    @Unique
    private Path lightload$resolve(String... paths) {
        String key = Arrays.toString(paths);
        Map<String, Path> resolvedPaths = lightload$resolvedPaths();
        Path cached = resolvedPaths.get(key);
        if (lightload$isUsable(cached)) return cached;

        Path resolved;
        if (lightload$modFile != null) {
            resolved = lightload$modFile.findResource(paths);
        } else {
            resolved = root;
            for (String path : paths) resolved = resolved.resolve(path);
        }
        if (lightload$isUsable(resolved)) resolvedPaths.put(key, resolved);
        return resolved;
    }

    @Unique
    private Map<String, Path> lightload$resolvedPaths() {
        Map<String, Path> paths = lightload$resolvedPaths;
        if (paths != null) return paths;
        synchronized (this) {
            if (lightload$resolvedPaths == null) lightload$resolvedPaths = new ConcurrentHashMap<>();
            return lightload$resolvedPaths;
        }
    }

    @Unique
    private Set<String> lightload$scheduledPathScans() {
        Set<String> scans = lightload$scheduledPathScans;
        if (scans != null) return scans;
        synchronized (this) {
            if (lightload$scheduledPathScans == null) {
                lightload$scheduledPathScans = ConcurrentHashMap.newKeySet();
            }
            return lightload$scheduledPathScans;
        }
    }

    @Unique
    private Map<PackType, Set<String>> lightload$namespaceIndex() {
        Map<PackType, Set<String>> index = lightload$namespaceIndex;
        if (index != null) return index;
        synchronized (this) {
            if (lightload$namespaceIndex == null) lightload$namespaceIndex = new ConcurrentHashMap<>();
            return lightload$namespaceIndex;
        }
    }

    @Unique
    private Map<String, List<String>> lightload$resourcePathIndex() {
        Map<String, List<String>> index = lightload$resourcePathIndex;
        if (index != null) return index;
        synchronized (this) {
            if (lightload$resourcePathIndex == null) lightload$resourcePathIndex = new ConcurrentHashMap<>();
            return lightload$resourcePathIndex;
        }
    }

    @Unique
    private ResourceIndexCache.PackView lightload$view(ResourceIndexCache cache) {
        if (lightload$packViewResolved) return lightload$packView;
        synchronized (this) {
            if (!lightload$packViewResolved) {
                lightload$packView = cache.packView(lightload$self());
                lightload$packViewResolved = true;
            }
            return lightload$packView;
        }
    }

    @Unique
    private static boolean lightload$isUsable(Path path) {
        try {
            return path != null && path.getFileSystem().isOpen();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
