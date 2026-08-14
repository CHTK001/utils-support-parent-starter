package com.chua.test.impl;

import com.chua.ast.support.annotation.SpiExtension;
import com.chua.test.spi.EmbeddingClient;

/**
 * Auto-derived interface + class-name-derived alias (Bge).
 */
@SpiExtension
public class BgeEmbeddingClient implements EmbeddingClient {

    @Override
    public float[] embedding(String text) {
        return new float[0];
    }
}
