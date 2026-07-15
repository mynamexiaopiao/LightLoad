package com.lightload.cache;

import com.lightload.core.ModFileBackedResourcePack;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stable identity for one namespace's ordered Forge mod-pack candidates. */
public final class ResourceRouteLayout {
    private ResourceRouteLayout() {
    }

    public static Optional<String> create(PackType type, String namespace, List<PackResources> candidates) {
        if (candidates.isEmpty()) return Optional.empty();
        List<String> identities = new ArrayList<>(candidates.size());
        for (PackResources candidate : candidates) {
            Optional<String> identity = candidateIdentity(candidate);
            if (identity.isEmpty()) return Optional.empty();
            identities.add(identity.get());
        }
        return Optional.of(fingerprint(type.name(), namespace, identities));
    }

    public static Optional<String> create(PackType type, String namespace, List<PackResources> candidates,
                                          Map<PackResources, String> candidateIdentities) {
        if (candidates.isEmpty()) return Optional.empty();
        List<String> identities = new ArrayList<>(candidates.size());
        for (PackResources candidate : candidates) {
            String identity = candidateIdentities.get(candidate);
            if (identity == null) return Optional.empty();
            identities.add(identity);
        }
        return Optional.of(fingerprint(type.name(), namespace, identities));
    }

    public static Optional<String> candidateIdentity(PackResources candidate) {
        if (!(candidate instanceof ModFileBackedResourcePack backed)) return Optional.empty();
        String identity = backed.lightload$cacheIdentity();
        if (identity == null) return Optional.empty();
        return Optional.of(candidate.getClass().getName() + '\n' + candidate.packId() + '\n' + identity);
    }

    static String fingerprint(String type, String namespace, List<String> identities) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "lightload-route-layout-v1");
            update(digest, type);
            update(digest, namespace);
            for (String identity : identities) update(digest, identity);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
