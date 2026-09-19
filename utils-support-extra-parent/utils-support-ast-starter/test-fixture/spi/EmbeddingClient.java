package com.chua.test.spi;

/**
 * @author CH
 * 测试 SPI 接口 fixture.
 * @since 4.0.0
 */
public interface EmbeddingClient {

    /**
     * 嵌入.
     *
     * @param text 输入 文本
     * @return embedding 向量
     */
    float[] embedding(String text);
}

