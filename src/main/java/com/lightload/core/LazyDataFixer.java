package com.lightload.core;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;

import java.util.Objects;
import java.util.function.Supplier;

public final class LazyDataFixer implements DataFixer {
    private final Supplier<DataFixer> factory;
    private volatile DataFixer delegate;

    public LazyDataFixer(Supplier<DataFixer> factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    public boolean initialized() {
        return delegate != null;
    }

    public void prewarm() {
        delegate();
    }

    private DataFixer delegate() {
        DataFixer current = delegate;
        if (current != null) return current;
        synchronized (this) {
            if (delegate == null) delegate = factory.get();
            return delegate;
        }
    }

    @Override
    public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int version, int newVersion) {
        return version >= newVersion ? input : delegate().update(type, input, version, newVersion);
    }

    @Override
    public Schema getSchema(int key) {
        return delegate().getSchema(key);
    }
}
