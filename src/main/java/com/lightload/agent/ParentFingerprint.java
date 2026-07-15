package com.lightload.agent;

import java.nio.file.Path;

public record ParentFingerprint(String path, long size, long modifiedMillis) {
    public static ParentFingerprint unavailable(Path path) {
        return new ParentFingerprint(path.toAbsolutePath().normalize().toString(), -1, -1);
    }
}
