# Lightload

Lightload is a client startup optimizer for Minecraft 1.21.1 and NeoForge 21.1. It combines early NeoForge
metadata acceleration with fingerprinted resource caches, ordered parallel pack lookup, model
micro-caches, lazy startup work, and compatibility profiles. It does not parallelize mod lifecycle
events, OpenGL work, or arbitrary third-party resource packs.

## Build

Use a Java 21 Gradle launcher. The project toolchain compiles the mod for Java 21.

```powershell
.\gradlew.bat test build
```

The reobfuscated mod jar is written to `build/libs/lightload-1.0.0.jar`.

## Configuration

The first client run creates `config/lightload.properties`:

```properties
enabled=true
memoryCache=true
persistentCache=true
profiling=true
showLoadingTime=true
cacheResourceRoutes=true
cacheResourceExistence=true
cacheResourceContent=true
maxContentCacheMiB=256
maxCachedResourceKiB=1024
parallelResourceLookup=true
parallelLookupMinPacks=4
isolateModdedResourceReloadFailures=true
isolatedResourceReloadListenerPatterns=*
connectorCompatibilityMode=true
aggressiveStartup=true
dedicatedReloadExecutor=true
reloadThreads=0
lazyDataFixer=true
progressiveModelWarmup=true
fastTextureStitching=true
earlyAgent=true
parallelJarJarMetadata=true
jarJarThreads=4
lightspeedModernFixInterop=true
optimizationProfile=auto
```

Cached indexes are stored under `cache/lightload`. Set `-Dlightload.disable=true` to disable all
Mixins before they are applied. If a startup ends before Forge finishes loading, the next launch
automatically disables cache optimizations once and clears recovery mode after a successful load.

Production mod jars are cached. Development directories, folder resource packs, nested paths that
cannot be fingerprinted safely, parsed JSON, baked models, textures, and open streams are never
persisted.

During the Mojang/NeoForge loading overlay, Lightload displays the live elapsed time. The completed
startup time remains visible above NeoForge's branding in the title screen's bottom-left corner. Set
`showLoadingTime=false` to hide both displays without disabling profiling or caching.

The path index records namespaces and relative resource paths after a non-blocking first scan and
replays them on later reloads and launches. Route and existence caches avoid losing pack probes;
the content cache stores only bounded text resources such as JSON, metadata, language files, and
shaders. Every persistent entry is tied to the mod JAR fingerprint and stored in a checked binary
format. When ModernFix is installed, Lightload avoids overlapping resource/model layers. When
LightspeedRe is installed, duplicate Lightspeed-compatible mixins are skipped.

The resource and model optimization behavior was independently adapted from the LGPL-3.0
Lightspeed project by CCr4ft3r, with additional cache validation, invalidation, and compatibility
fallbacks for this implementation.
