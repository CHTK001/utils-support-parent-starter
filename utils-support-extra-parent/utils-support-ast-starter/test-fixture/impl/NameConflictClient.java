package com.chua.test.impl;

import com.chua.ast.support.annotation.AutoSpi;
import com.chua.common.support.spi.annotations.Extension;
import com.chua.test.spi.EmbeddingClient;

/**
 * Explicit {@code @AutoSpi(name = ...)} + {@code @Extension} coexist: the explicit name is ignored
 * at runtime (annotation names win), so a compile-time warning is expected and only a bare
 * discovery line is generated.
 *
 * @author CH
 * @since 4.0.0.42
 */
@AutoSpi(name = "conflict")
@Extension("conflictname")
public class NameConflictClient implements EmbeddingClient {

    @Override
    /** Embedding */
    public float[] embedding(String text) {
        return new float[0];
    }
}

