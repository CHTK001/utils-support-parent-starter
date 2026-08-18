package com.chua.test.spi;

/**
 * @author CH
 * Test SPI interface fixture.
 */
public interface EmbeddingClient {

    /**
     * Embedding.
     *
     * @param text input text
     * @return embedding vector
     */
    float[] embedding(String text);
}

