package com.lightload.agent;

import java.util.List;

public record JarJarIndex(String targetFingerprint, List<ParentFingerprint> parents, List<Dependency> dependencies) {
    public JarJarIndex {
        parents = List.copyOf(parents);
        dependencies = List.copyOf(dependencies);
    }

    public record Dependency(int parentIndex, List<String> nestedPaths, String identity) {
        public Dependency {
            nestedPaths = List.copyOf(nestedPaths);
        }
    }
}
