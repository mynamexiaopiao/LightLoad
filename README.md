# Lightload

Lightload is a client startup optimizer for Minecraft 1.21.1 and NeoForge 21.1. It combines early NeoForge
metadata acceleration with fingerprinted resource caches, ordered parallel pack lookup, model
micro-caches, lazy startup work, and compatibility profiles. It also packages an early bootstrap and
an embedded GAME-layer payload in the distributable JAR. It does not parallelize mod lifecycle
events, OpenGL work, or arbitrary third-party resource packs.

## Build

Use a Java 21 Gradle launcher. The project toolchain compiles the mod for Java 21.

```powershell
.\gradlew.bat test build
```

The single-file bootstrap distribution is written to `build/libs/lightload-1.0.0.jar`; it does not
require a separate `-javaagent` or companion bootstrap JAR.

## Configuration

The first client run creates `config/lightload-client.toml`:

```toml
enabled=true
memoryCache=true
persistentCache=true
profiling=true
showLoadingTime=true
cacheResourceRoutes=false
cacheResourceExistence=true
maxExistenceCacheMiB=256
cacheResourceContent=false
maxContentCacheMiB=256
maxCachedResourceKiB=1024
parallelResourceLookup=true
parallelLookupMinPacks=16
isolateModdedResourceReloadFailures=true
isolatedResourceReloadListenerPatterns=
connectorCompatibilityMode=true
aggressiveStartup=true
dedicatedReloadExecutor=true
reloadThreads=0
lazyDataFixer=false
progressiveModelWarmup=true
fastTextureStitching=true
earlyAgent=true
parallelJarJarMetadata=false
jarJarThreads=4
lightspeedModernFixInterop=true
optimizationProfile=auto
```

Cached indexes are stored under `cache/lightload`. Set `-Dlightload.disable=true` to disable all
Mixins before they are applied. If a startup ends before NeoForge finishes loading, the next launch
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
format. ZIP resource entry indexes use the archive path, size, modification time, and overlay prefix
as their fingerprint and are written atomically after startup. When ModernFix is installed, Lightload avoids overlapping resource/model layers. When
Forgified Fabric API is installed, Lightload preserves its identity-sensitive reload listener sorting
while retaining the remaining startup and resource optimizations. When LightspeedRe is installed,
duplicate Lightspeed-compatible mixins are skipped.

The outer JAR is discovered through NeoForge's candidate-locator service, extracts its content-addressed
GAME payload under `cache/lightload/bundle`, and attaches the optional runtime Agent early in loading.
Replacing the distribution creates a new payload automatically.

The resource and model optimization behavior was independently adapted from the LGPL-3.0
Lightspeed project by CCr4ft3r, with additional cache validation, invalidation, and compatibility
fallbacks for this implementation.
