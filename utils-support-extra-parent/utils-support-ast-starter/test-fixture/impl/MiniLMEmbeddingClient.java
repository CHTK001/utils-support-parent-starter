package com.chua.test.impl;

import com.chua.ast.support.annotation.SpiExtension;
import com.chua.test.spi.EmbeddingClient;

/**
 * Explicit interface + alias registration.
 */
@SpiExtension(value = "com.chua.test.spi.EmbeddingClient", name = "minilm")
public class MiniLMEmbeddingClient implements EmbeddingClient {

    @Override
    public float[] embedding(String text) {
        return new float[0];
    }
}
