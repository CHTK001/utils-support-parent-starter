package com.chua.test.spi;

/**
 * Test SPI interface (mimics com.chua.common.support.ai.embedding.EmbeddingClient).
 */
public interface EmbeddingClient {

    /**
     * Embed text to vector.
     *
     * @param text input text
     * @return vector
     */
    float[] embedding(String text);
}
