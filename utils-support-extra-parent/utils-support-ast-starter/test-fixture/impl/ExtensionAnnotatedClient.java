package com.chua.test.impl;

import com.chua.ast.support.annotation.AutoSpi;
import com.chua.common.support.spi.annotations.Extension;
import com.chua.test.spi.EmbeddingClient;

/**
 * @Extension + @AutoSpi: runtime prefers annotation names, index only needs a bare discovery line
 * (no {@code extjson=...} alias line, avoiding N×M duplicate registration).
 *
 * @author CH
 * @since 4.0.0.42
 */
@AutoSpi
@Extension("extjson")
public class ExtensionAnnotatedClient implements EmbeddingClient {

    @Override
    /** Embedding */
    public float[] embedding(String text) {
        return new float[0];
    }
}

