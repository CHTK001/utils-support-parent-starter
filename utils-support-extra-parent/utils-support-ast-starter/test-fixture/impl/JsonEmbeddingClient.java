package com.chua.test.impl;

import com.chua.ast.support.annotation.SpiExtension;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.test.spi.EmbeddingClient;

/**
 * Alias derived from the @Spi annotation mirror (string-matched by FQN).
 */
@SpiExtension
@Spi({"json", "application/json"})
public class JsonEmbeddingClient implements EmbeddingClient {

    @Override
    public float[] embedding(String text) {
        return new float[0];
    }
}
