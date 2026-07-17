# Lightload

Lightload is a client startup optimizer for Minecraft 1.20.1 and Forge 47. It combines early Forge
metadata acceleration with fingerprinted resource caches, ordered parallel pack lookup, model
micro-caches, lazy startup work, and compatibility profiles. It does not parallelize mod lifecycle
events, OpenGL work, or arbitrary third-party resource packs.

## Build

Use a Java 17 or newer Gradle launcher. The project toolchain compiles the mod for Java 17.

```powershell
.\gradlew.bat test build
```

The reobfuscated single-file distribution is written to `build/libs/lightload-1.0.0.jar`. Install
only this JAR in the `mods` directory. It contains an isolated early bootstrap and an embedded
GAME-layer mod payload; no `-javaagent` or companion bootstrap JAR is required.

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
maxExistenceCacheMiB=256
cacheResourceContent=true
maxContentCacheMiB=256
maxCachedResourceKiB=1024
parallelResourceLookup=true
parallelLookupMinPacks=16
isolateModdedResourceReloadFailures=true
isolatedResourceReloadListenerPatterns=*
connectorCompatibilityMode=true
lazyBlockStateCache=true
aggressiveStartup=true
earlyAgent=true
parallelJarJarMetadata=true
jarJarThreads=4
lightspeedModernFixInterop=true
optimizationProfile=auto
```

Cached indexes are stored under `cache/lightload`. Set `enabled=false` in the configuration to
disable Lightload. If a startup ends before Forge finishes loading, the next launch
automatically disables cache optimizations once and clears recovery mode after a successful load.

Production mod jars are cached. Development directories, folder resource packs, nested paths that
cannot be fingerprinted safely, parsed JSON, baked models, textures, and open streams are never
persisted.

During the Mojang/Forge loading overlay, Lightload displays the live elapsed time. The completed
startup time remains visible above Forge's branding in the title screen's bottom-left corner. Set
`showLoadingTime=false` to hide both displays without disabling profiling or caching.

The path index records namespaces and relative resource paths after a non-blocking first scan and
replays them on later reloads and launches. Route and existence caches avoid losing pack probes;
the content cache stores only bounded text resources such as JSON, metadata, language files, and
shaders. Every persistent entry is tied to the mod JAR fingerprint and stored in a checked binary
format. When ModernFix is installed, Lightload avoids overlapping resource/model layers. When
LightspeedRe is installed, duplicate Lightspeed-compatible mixins are skipped.

Existence caches are enabled up to `maxExistenceCacheMiB`; large Forge packs such as ATM9 can
disable this cache and use the much smaller route index instead when the existence shards grow too
large.
Parallel pack lookup runs in bounded waves so an early match does not schedule every later pack.
The outer JAR is discovered in Forge's SERVICE layer, activates the early metadata optimization,
and exposes its content-addressed embedded payload to Forge as a normal GAME-layer mod. Extracted
payloads are verified and stored under `cache/lightload/bundle`; replacing the distribution creates
a new payload automatically.

The resource and model optimization behavior was independently adapted from the LGPL-3.0
Lightspeed project by CCr4ft3r, with additional cache validation, invalidation, and compatibility
fallbacks for this implementation.
