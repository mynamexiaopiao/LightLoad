package com.lightload.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public record PackFingerprint(String packId, String source, long size, long modifiedMillis) {
    public static Optional<PackFingerprint> create(String packId, Path source) {
        try {
            Path absolute = source.toAbsolutePath().normalize();
            if (!"file".equalsIgnoreCase(absolute.toUri().getScheme()) || !Files.isRegularFile(absolute)) {
                return Optional.empty();
            }
            return Optional.of(new PackFingerprint(
                    packId,
                    absolute.toString(),
                    Files.size(absolute),
                    Files.getLastModifiedTime(absolute).toMillis()
            ));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    public String key() {
        return packId + '\n' + source;
    }
}
