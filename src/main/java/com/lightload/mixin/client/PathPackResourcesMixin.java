package com.lightload.mixin.client;

import com.lightload.cache.PackFingerprint;
import com.lightload.cache.ResourceIndexCache;
import com.lightload.core.ModFileBackedResourcePack;
import com.lightload.core.FusionPackCompat;
import com.lightload.core.StartupCoordinator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.resource.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

@Mixin(value = PathPackResources.class, remap = false)
public abstract class PathPackResourcesMixin implements ModFileBackedResourcePack {
    @Shadow protected abstract Path resolve(String... paths);

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

    @Override
    public void lightload$bindModFile(IModFile modFile) {
        lightload$modFile = modFile;
        lightload$cacheIdentity = PackFingerprint.create(lightload$self().packId(), modFile.getFilePath())
                .map(fingerprint -> fingerprint.packId() + '\n' + fingerprint.source() + '\n'
                        + fingerprint.size() + '\n' + fingerprint.modifiedMillis())
                .orElse(null);
        lightload$routeSafe = lightload$cacheIdentity != null && !FusionPackCompat.hasOverrides(this);
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache != null) cache.bindModFile(lightload$self(), modFile);
    }

    @Override
    public void lightload$startCachePreload() {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache != null) cache.startCachePreload(lightload$self());
    }

    @Override
    public String lightload$cacheIdentity() {
        return lightload$routeSafe ? lightload$cacheIdentity : null;
    }

    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$findResolvedPath(String[] paths, CallbackInfoReturnable<Path> cir) {
        if (!StartupCoordinator.optimizationsActive()) return;
        String key = Arrays.toString(paths);
        Map<String, Path> resolvedPaths = lightload$resolvedPaths();
        Path cached = resolvedPaths.get(key);
        if (lightload$isUsable(cached)) {
            cir.setReturnValue(cached);
            return;
        }
        if (lightload$modFile == null) return;
        Path resolved = lightload$modFile.findResource(paths);
        if (lightload$isUsable(resolved)) resolvedPaths.put(key, resolved);
        cir.setReturnValue(resolved);
    }

    @Inject(method = "resolve", at = @At("RETURN"), require = 0)
    private void lightload$recordResolvedPath(String[] paths, CallbackInfoReturnable<Path> cir) {
        if (!StartupCoordinator.optimizationsActive() || lightload$modFile != null) return;
        Path resolved = cir.getReturnValue();
        if (lightload$isUsable(resolved)) lightload$resolvedPaths().put(Arrays.toString(paths), resolved);
    }

    @Inject(method = {"getNamespaces", "m_5698_"}, at = @At("HEAD"), cancellable = true,
            require = 0, remap = false)
    private void lightload$findNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache == null || !cache.pathIndexEnabled() || FusionPackCompat.hasOverrides(this)) return;
        cache.findNamespaces(lightload$self(), type).ifPresent(cir::setReturnValue);
    }

    @Inject(method = {"getNamespaces", "m_5698_"}, at = @At("RETURN"), require = 0, remap = false)
    private void lightload$recordNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        Set<String> namespaces = cir.getReturnValue();
        if (cache != null && cache.pathIndexEnabled() && namespaces != null
                && !FusionPackCompat.hasOverrides(this)) {
            cache.recordNamespaces(lightload$self(), type, namespaces);
            namespaces.forEach(namespace -> lightload$schedulePathScan(cache, type, namespace));
        }
    }

    @Inject(method = {"listResources", "m_8031_"}, at = @At("HEAD"), cancellable = true,
            require = 0, remap = false)
    private void lightload$listIndexedResources(PackType type, String namespace, String path,
                                                PackResources.ResourceOutput output, CallbackInfo ci) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache == null || !cache.pathIndexEnabled() || FusionPackCompat.hasOverrides(this)) return;
        Optional<List<String>> cached = cache.findResourcePaths(lightload$self(), type, namespace);
        if (cached.isEmpty()) {
            lightload$schedulePathScan(cache, type, namespace);
            return;
        }

        String requested = path.isEmpty() ? "" : path.endsWith("/") ? path : path + "/";
        for (String relative : cached.get()) {
            if (!relative.startsWith(requested)) continue;
            ResourceLocation location = ResourceLocation.tryBuild(namespace, relative);
            if (location != null) output.accept(location, lightload$openResource(type, location));
        }
        ci.cancel();
    }

    @Unique
    private void lightload$schedulePathScan(ResourceIndexCache cache, PackType type, String namespace) {
        String key = type.name() + '|' + namespace;
        if (!lightload$scheduledPathScans().add(key)) return;
        cache.scanAsync(() -> {
            Path root = resolve(type.getDirectory(), namespace);
            List<String> paths = lightload$scanPaths(root);
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

    @Inject(method = {"getResource", "m_214146_"}, at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void lightload$findResource(PackType type, ResourceLocation location,
                                        CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (!StartupCoordinator.optimizationsActive() || cache == null) return;
        Optional<Boolean> exists = cache.packExistenceCacheEnabled()
                ? cache.findCachedExistence(lightload$self(), type, location)
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

    @Inject(method = {"getResource", "m_214146_"}, at = @At("RETURN"), cancellable = true,
            require = 1, remap = false)
    private void lightload$recordResource(PackType type, ResourceLocation location,
                                          CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (StartupCoordinator.optimizationsActive() && cache != null) {
            IoSupplier<InputStream> original = cir.getReturnValue();
            if (cache.packExistenceCacheEnabled()) {
                cache.recordExistence(lightload$self(), type, location, original != null);
            }
            if (original != null && !FusionPackCompat.hasOverrides(this)) {
                cir.setReturnValue(cache.wrapContent(lightload$self(), type, location, original));
            }
        }
    }

    @Inject(method = {"getRootResource", "m_8017_"}, at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void lightload$findRootResource(String[] paths,
                                            CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (!StartupCoordinator.optimizationsActive() || cache == null) return;
        if (!cache.packExistenceCacheEnabled()) return;
        Optional<Boolean> exists = cache.findCachedRootExistence(lightload$self(), paths);
        if (exists.isEmpty()) return;
        cir.setReturnValue(exists.get() ? lightload$openRootResource(Arrays.copyOf(paths, paths.length)) : null);
    }

    @Inject(method = {"getRootResource", "m_8017_"}, at = @At("RETURN"), require = 1, remap = false)
    private void lightload$recordRootResource(String[] paths,
                                              CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (StartupCoordinator.optimizationsActive() && cache != null && cache.packExistenceCacheEnabled()) {
            cache.recordRootExistence(lightload$self(), paths, cir.getReturnValue() != null);
        }
    }

    @Unique
    private IoSupplier<InputStream> lightload$openResource(PackType type, ResourceLocation location) {
        String[] parts = location.getPath().split("/");
        String[] resolved = new String[parts.length + 2];
        resolved[0] = type.getDirectory();
        resolved[1] = location.getNamespace();
        System.arraycopy(parts, 0, resolved, 2, parts.length);
        return () -> Files.newInputStream(resolve(resolved));
    }

    @Unique
    private IoSupplier<InputStream> lightload$openRootResource(String[] paths) {
        return () -> {
            Path resolved = resolve(paths);
            if (resolved == null) throw new NoSuchFileException(Arrays.toString(paths));
            return Files.newInputStream(resolved);
        };
    }

    @Unique
    private PathPackResources lightload$self() {
        return (PathPackResources) (Object) this;
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
    private static boolean lightload$isUsable(Path path) {
        try {
            return path != null && path.getFileSystem().isOpen();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
